package io.astra.doctype.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.kernel.extension.HookRegistry;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JSONB document store. Every operation runs in one transaction that first binds the current
 * tenant into a transaction-local PostgreSQL setting; row-level security does the rest.
 * The tenant is never a parameter: it is read from {@link TenantContext}.
 */
@ApplicationScoped
public class DocumentStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    @Inject AgroalDataSource dataSource;
    @Inject ObjectMapper json;
    @Inject DocTypeRegistry registry;
    @Inject HookRegistry hooks;
    @Inject SeriesService series;

    public List<Document> list(String doctype, int limit, int offset) {
        registry.require(doctype);
        return inTenantTx(conn -> {
            List<Document> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM astra_doc WHERE doctype = ? ORDER BY modified_at DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, doctype);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            return out;
        });
    }

    public Optional<Document> get(String doctype, String name) {
        registry.require(doctype);
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

    public Document insert(String doctype, Map<String, Object> data, String owner) {
        DocTypeDefinition def = registry.require(doctype);
        Document doc = new Document(doctype, data).owner(owner);
        DocumentValidator.applyDefaultsAndValidate(def, doc);
        hooks.beforeSave(doc);
        return inTenantTx(conn -> {
            doc.name(assignName(conn, def, doc));
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO astra_doc (id, tenant_id, doctype, name, owner, data) VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING *")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, TenantContext.require());
                ps.setString(3, doctype);
                ps.setString(4, doc.name());
                ps.setString(5, owner);
                ps.setString(6, json.writeValueAsString(doc.data()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    Document saved = read(rs);
                    hooks.afterSave(saved);
                    return saved;
                }
            }
        });
    }

    public Optional<Document> update(String doctype, String name, Map<String, Object> patch) {
        DocTypeDefinition def = registry.require(doctype);
        return inTenantTx(conn -> {
            Optional<Document> existing = get(doctype, name);
            if (existing.isEmpty()) return Optional.empty();
            Document doc = existing.get();
            if (doc.docstatus() == 1) throw new DocValidationException("submitted documents are immutable");
            patch.forEach(doc::set);
            DocumentValidator.applyDefaultsAndValidate(def, doc);
            hooks.beforeSave(doc);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE astra_doc SET data = ?::jsonb, modified_at = now(), version = version + 1 "
                            + "WHERE doctype = ? AND name = ? AND version = ? RETURNING *")) {
                ps.setString(1, json.writeValueAsString(doc.data()));
                ps.setString(2, doctype);
                ps.setString(3, name);
                ps.setInt(4, doc.version());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new DocValidationException("concurrent modification of " + doctype + "/" + name);
                    Document saved = read(rs);
                    hooks.afterSave(saved);
                    return Optional.of(saved);
                }
            }
        });
    }

    private String assignName(Connection conn, DocTypeDefinition def, Document doc) throws SQLException {
        if (def.naming().byField()) {
            String v = doc.getString(def.naming().field());
            if (v == null || v.isBlank()) throw new DocValidationException("naming field '" + def.naming().field() + "' is empty");
            return v;
        }
        if (def.naming().bySeries()) return series.next(conn, def.naming().series());
        return UUID.randomUUID().toString();
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
                    .version(rs.getInt("version"));
        } catch (java.io.IOException e) {
            throw new SQLException("corrupt JSONB payload", e);
        }
    }

    /** Opens a transaction, binds the tenant for row-level security, runs the work, commits. */
    private <T> T inTenantTx(SqlWork<T> work) {
        String tenant = TenantContext.require();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('astra.tenant', ?, true)")) {
                    ps.setString(1, tenant);
                    ps.execute();
                }
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database error", e);
        }
    }

    @FunctionalInterface
    interface SqlWork<T> { T run(Connection conn) throws Exception; }
}
