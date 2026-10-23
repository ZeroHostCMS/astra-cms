package io.astra.doctype.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.Action;
import io.astra.doctype.registry.DocTypeOverlays;
import io.astra.kernel.extension.HookRegistry;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;
import io.astra.spi.doctype.FieldType;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSONB document store. Every operation runs in one transaction that first binds the current
 * tenant into a transaction-local PostgreSQL setting; row-level security does the rest.
 * The tenant is never a parameter: it is read from {@link TenantContext}.
 *
 * <p>Calls made while a transaction is open (for example from a lifecycle hook through
 * {@link DocumentService} or the general ledger) join that transaction, so side effects are atomic.
 */
@ApplicationScoped
public class DocumentStore implements DocumentService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Pattern AMEND_SUFFIX = Pattern.compile("^(.*)-(\\d+)$");

    @Inject TenantTransactions tx;
    @Inject Instance<GlobalTransactionCoordinator> coordinator;
    @Inject ObjectMapper json;
    @Inject DocTypeOverlays overlays;
    @Inject HookRegistry hooks;
    @Inject SeriesService series;
    @Inject DocPermissions permissions;

    /**
     * Lists documents, newest first. {@code filters} match declared fields by equality (the generated
     * expression is the one projected fields are indexed on) or the core {@code docstatus} column.
     */
    public List<Document> list(String doctype, Map<String, String> filters, int limit, int offset) {
        return page(doctype, filters, limit, offset, null).docs();
    }

    /** One page of a list and the cursor of the page after it ({@code null} when this page is the last). */
    public record Page(List<Document> docs, String next) { }

    /**
     * Lists documents, newest first, continuing after {@code after} (a cursor from a previous page) when given.
     * A cursor costs the same on page 1 and page 10,000: the query seeks to {@code (modified_at, name)} in the
     * index instead of reading and discarding {@code offset} rows (200 ms at offset 200,000 on 5 million rows,
     * 0.2 ms with a cursor). Ties on {@code modified_at} are broken by {@code name}, unique within a DocType.
     */
    public Page page(String doctype, Map<String, String> filters, int limit, int offset, String after) {
        DocTypeDefinition def = overlays.effective(doctype);
        StringBuilder sql = new StringBuilder("SELECT * FROM astra_doc WHERE doctype = ?");
        List<Object> params = new ArrayList<>(List.of(doctype));
        for (Map.Entry<String, String> f : filters.entrySet()) {
            if (f.getKey().equals("docstatus")) {
                sql.append(" AND docstatus = ?");
                params.add(parseDocstatus(f.getValue()));
            } else {
                FieldDefinition field = def.field(f.getKey())
                        .orElseThrow(() -> new DocValidationException("unknown filter '" + f.getKey() + "' for DocType " + doctype));
                sql.append(" AND ").append(ProjectionIndexer.expression(field)).append(" = ?");
                params.add(f.getValue());
            }
        }
        if (after != null && !after.isBlank()) {
            ListCursor cursor = ListCursor.parse(after);
            sql.append(" AND (modified_at, name) < (?, ?)");
            params.add(java.time.OffsetDateTime.ofInstant(cursor.modifiedAt(), java.time.ZoneOffset.UTC));
            params.add(cursor.name());
        }
        sql.append(" ORDER BY modified_at DESC, name DESC LIMIT ? OFFSET ?");
        params.add(limit + 1); // one extra row tells whether another page exists
        params.add(offset);
        return inTenantTx(conn -> {
            List<Document> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            if (out.size() <= limit) return new Page(out, null);
            List<Document> docs = out.subList(0, limit);
            Document last = docs.getLast();
            return new Page(List.copyOf(docs), new ListCursor(last.modifiedAt(), last.name()).encode());
        });
    }

    @Override
    public List<Document> find(String doctype, Map<String, String> filters, int limit) {
        return list(doctype, filters, limit, 0);
    }

    @Override
    public Optional<Document> get(String doctype, String name) {
        overlays.effective(doctype);
        return inTenantTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM astra_doc WHERE doctype = ? AND name = ?")) {
                ps.setString(1, doctype);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Document insert(String doctype, Map<String, Object> data, String owner) {
        DocTypeDefinition def = overlays.effective(doctype);
        DocumentValidator.rejectReadOnly(def, data);
        return inTenantTx(conn -> persistNew(conn, def, new Document(doctype, data).owner(owner)));
    }

    @Override
    public Optional<Document> update(String doctype, String name, Map<String, Object> patch) {
        return update(doctype, name, patch, existing -> { });
    }

    /**
     * Updates a draft; {@code check} sees the stored document before anything changes (the REST resource
     * evaluates write permission, including if-owner rules, there). Reading, checking and writing happen in
     * one transaction, so a request costs one round of tenant binding and one read, not two.
     */
    public Optional<Document> update(String doctype, String name, Map<String, Object> patch,
                                     java.util.function.Consumer<Document> check) {
        DocTypeDefinition def = overlays.effective(doctype);
        return inTenantTx(conn -> {
            Optional<Document> existing = get(doctype, name);
            if (existing.isEmpty()) return Optional.empty();
            Document doc = existing.get();
            check.accept(doc);
            DocumentValidator.rejectReadOnly(def, patch);
            requireDraft(doc);
            patch.forEach(doc::set);
            DocumentValidator.applyDefaultsAndValidate(def, doc);
            hooks.beforeSave(doc);
            validateLinks(conn, def, doc);
            Document saved = write(conn, doc, doc.docstatus());
            hooks.afterSave(saved);
            return Optional.of(saved);
        });
    }

    /**
     * Draft → Submitted. Submitted documents are immutable; {@code onSubmit} hooks run in the same transaction.
     * When a user is signed in, their submit permission is checked here too, so a hook that submits other
     * documents (through {@link DocumentService}) cannot do more than its caller may.
     */
    @Override
    public Optional<Document> submit(String doctype, String name) {
        DocTypeDefinition def = requireSubmittable(doctype);
        return global(def, "submit " + doctype + " " + name, () -> inTenantTx(conn -> {
            Optional<Document> existing = get(doctype, name);
            if (existing.isEmpty()) return Optional.empty();
            Document doc = existing.get();
            requireDraft(doc);
            requireCaller(doc, Action.SUBMIT);
            hooks.onSubmit(doc);
            DocumentValidator.applyDefaultsAndValidate(def, doc);
            validateLinks(conn, def, doc);
            return Optional.of(write(conn, doc, 1));
        }));
    }

    /**
     * Submitted → Cancelled. {@code onCancel} hooks reverse side effects in the same transaction or veto it.
     * A signed-in caller needs cancel permission, also when a hook cancels on their behalf.
     */
    @Override
    public Optional<Document> cancel(String doctype, String name) {
        DocTypeDefinition def = requireSubmittable(doctype);
        return global(def, "cancel " + doctype + " " + name, () -> inTenantTx(conn -> {
            Optional<Document> existing = get(doctype, name);
            if (existing.isEmpty()) return Optional.empty();
            Document doc = existing.get();
            if (doc.docstatus() != 1) throw new DocValidationException("only submitted documents can be cancelled");
            requireCaller(doc, Action.CANCEL);
            hooks.onCancel(doc);
            return Optional.of(write(conn, doc, 2));
        }));
    }

    /**
     * Runs a submit or cancel inside a global transaction when the DocType asks for one and a coordinator
     * is connected; the kernel's database transaction inside commits last, then the global one.
     */
    private <T> T global(DocTypeDefinition def, String name, Supplier<T> work) {
        if (def.globalTransaction() && coordinator.isResolvable() && coordinator.get().available()) {
            return coordinator.get().inGlobal(name, work);
        }
        return work.get();
    }

    /** Cancelled → new draft revision named {@code <original>-1}, {@code -2}, … linked through {@code amendedFrom}. */
    public Optional<Document> amend(String doctype, String name, String owner) {
        DocTypeDefinition def = requireSubmittable(doctype);
        return inTenantTx(conn -> {
            Optional<Document> existing = get(doctype, name);
            if (existing.isEmpty()) return Optional.empty();
            Document cancelled = existing.get();
            if (cancelled.docstatus() != 2) throw new DocValidationException("only cancelled documents can be amended");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name FROM astra_doc WHERE doctype = ? AND amended_from = ?")) {
                ps.setString(1, doctype);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) throw new DocValidationException(name + " was already amended as " + rs.getString(1));
                }
            }
            // Read-only fields belong to the kernel and hooks; the new revision recomputes them.
            Map<String, Object> copy = new LinkedHashMap<>(cancelled.data());
            def.fields().stream().filter(FieldDefinition::readOnly).forEach(f -> copy.remove(f.name()));
            Document draft = new Document(doctype, copy).owner(owner)
                    .name(amendedName(cancelled)).amendedFrom(name);
            return Optional.of(persistNew(conn, def, draft));
        });
    }

    private Document persistNew(Connection conn, DocTypeDefinition def, Document doc) throws SQLException {
        DocumentValidator.applyDefaultsAndValidate(def, doc);
        hooks.beforeSave(doc);
        validateLinks(conn, def, doc);
        if (doc.name() == null) doc.name(assignName(conn, def, doc));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO astra_doc (id, tenant_id, doctype, name, owner, data, amended_from) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING *")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, TenantContext.require());
            ps.setString(3, doc.doctype());
            ps.setString(4, doc.name());
            ps.setString(5, doc.owner());
            ps.setString(6, toJson(doc.data()));
            ps.setString(7, doc.amendedFrom());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Document saved = read(rs);
                hooks.afterSave(saved);
                return saved;
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new DocValidationException(doc.doctype() + " '" + doc.name() + "' already exists");
            }
            throw e;
        }
    }

    /** Writes data and docstatus with an optimistic version check. */
    private Document write(Connection conn, Document doc, int docstatus) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE astra_doc SET data = ?::jsonb, docstatus = ?, modified_at = now(), version = version + 1 "
                        + "WHERE doctype = ? AND name = ? AND version = ? RETURNING *")) {
            ps.setString(1, toJson(doc.data()));
            ps.setInt(2, docstatus);
            ps.setString(3, doc.doctype());
            ps.setString(4, doc.name());
            ps.setInt(5, doc.version());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new DocValidationException("concurrent modification of " + doc.doctype() + "/" + doc.name());
                return read(rs);
            }
        }
    }

    /**
     * The signed-in user's permission for a lifecycle step, evaluated with their roles in the tenant being
     * written (none when that is not the request's tenant). Without a user (startup or background jobs,
     * kernel fixtures) there is no caller to check. The REST resource checks the same rule before calling in,
     * so for a direct request this repeats an identical decision.
     */
    private void requireCaller(Document doc, Action action) {
        TenantRequest request = Arc.container().requestContext().isActive()
                ? Arc.container().instance(TenantRequest.class).get() : null;
        if (request == null || request.userId() == null) return;
        Set<String> roles = TenantContext.require().equals(request.tenantId()) ? request.roles() : Set.of();
        if (action == Action.SUBMIT) permissions.requireSubmit(doc.doctype(), roles, request.userId(), doc);
        else permissions.require(doc.doctype(), roles, action);
    }

    /**
     * Every non-empty Link field must name an existing document of its target DocType in this tenant.
     * All Links of the document are checked with one statement (one round trip, however many Links).
     */
    private void validateLinks(Connection conn, DocTypeDefinition def, Document doc) throws SQLException {
        List<FieldDefinition> links = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (FieldDefinition f : def.fields()) {
            if (f.type() != FieldType.LINK || f.options().isEmpty()) continue;
            String target = doc.getString(f.name());
            if (target == null || target.isBlank()) continue;
            links.add(f);
            targets.add(target);
        }
        if (links.isEmpty()) return;
        StringBuilder sql = new StringBuilder("SELECT doctype, name FROM astra_doc WHERE ");
        for (int i = 0; i < links.size(); i++) sql.append(i == 0 ? "" : " OR ").append("(doctype = ? AND name = ?)");
        Set<String> found = new java.util.HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int p = 1;
            for (int i = 0; i < links.size(); i++) {
                ps.setString(p++, links.get(i).options().getFirst());
                ps.setString(p++, targets.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) found.add(rs.getString(1) + '\u0000' + rs.getString(2));
            }
        }
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            String target = links.get(i).options().getFirst();
            if (!found.contains(target + '\u0000' + targets.get(i))) {
                errors.add("field '" + links.get(i).name() + "': " + target + " '" + targets.get(i) + "' not found");
            }
        }
        if (!errors.isEmpty()) throw new DocValidationException(errors);
    }

    /** Fails with a validation error when the DocType has no submit lifecycle. */
    public DocTypeDefinition requireSubmittable(String doctype) {
        DocTypeDefinition def = overlays.effective(doctype);
        if (!def.submittable()) throw new DocValidationException("DocType " + doctype + " is not submittable");
        return def;
    }

    private static void requireDraft(Document doc) {
        if (doc.docstatus() == 1) throw new DocValidationException("submitted documents are immutable; cancel and amend instead");
        if (doc.docstatus() == 2) throw new DocValidationException("cancelled documents are immutable; amend instead");
    }

    private static String amendedName(Document cancelled) {
        Matcher m = AMEND_SUFFIX.matcher(cancelled.name());
        if (cancelled.amendedFrom() != null && m.matches()) {
            return m.group(1) + "-" + (Integer.parseInt(m.group(2)) + 1);
        }
        return cancelled.name() + "-1";
    }

    private static int parseDocstatus(String v) {
        return switch (v) {
            case "0", "1", "2" -> Integer.parseInt(v);
            default -> throw new DocValidationException("filter 'docstatus' must be 0, 1 or 2");
        };
    }

    private String assignName(Connection conn, DocTypeDefinition def, Document doc) throws SQLException {
        if (def.naming().byField()) {
            String v = doc.getString(def.naming().field());
            if (v == null || v.isBlank()) throw new DocValidationException("naming field '" + def.naming().field() + "' is empty");
            return v;
        }
        if (def.naming().bySeries()) return series.next(conn, def.naming().series(), def.naming().gapless());
        return UUID.randomUUID().toString();
    }

    private String toJson(Map<String, Object> data) {
        try {
            return json.writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DocValidationException("document data is not serialisable: " + e.getOriginalMessage());
        }
    }

    private Document read(ResultSet rs) throws SQLException {
        try {
            Map<String, Object> data = json.readValue(rs.getString("data"), MAP);
            return new Document(rs.getString("doctype"), data)
                    .name(rs.getString("name"))
                    .docstatus(rs.getInt("docstatus"))
                    .owner(rs.getString("owner"))
                    .createdAt(rs.getObject("created_at", Timestamp.class).toInstant())
                    .modifiedAt(rs.getObject("modified_at", Timestamp.class).toInstant())
                    .version(rs.getInt("version"))
                    .amendedFrom(rs.getString("amended_from"));
        } catch (java.io.IOException e) {
            throw new SQLException("corrupt JSONB payload", e);
        }
    }

    private <T> T inTenantTx(TenantTransactions.SqlWork<T> work) {
        return tx.inTenantTx(work);
    }
}
