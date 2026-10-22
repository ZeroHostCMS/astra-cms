package io.astra.doctype.registry;

import io.astra.doctype.store.TenantTransactions;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The DocType as the current tenant sees it: the app's definition with the tenant's overlay applied.
 * Overlays live in {@code astra_overlay} under row-level security and take effect without a restart;
 * other pods pick a change up within {@link #TTL_MILLIS}.
 */
@ApplicationScoped
public class DocTypeOverlays {

    private static final Logger LOG = Logger.getLogger(DocTypeOverlays.class);
    static final long TTL_MILLIS = 2_000;

    private record Cached(DocTypeDefinition def, long loadedAt) { }

    @Inject DocTypeRegistry registry;
    @Inject TenantTransactions tx;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** The effective DocType for the tenant in scope, or the app's definition outside a tenant scope. */
    public DocTypeDefinition effective(String doctype) {
        DocTypeDefinition base = registry.require(doctype);
        Optional<String> tenant = TenantContext.current();
        if (tenant.isEmpty()) return base;
        String key = tenant.get() + "|" + doctype;
        Cached c = cache.get(key);
        if (c != null && System.currentTimeMillis() - c.loadedAt() < TTL_MILLIS) return c.def();
        DocTypeDefinition def = base;
        Optional<Map<String, Object>> overlay = get(doctype);
        if (overlay.isPresent()) {
            try {
                def = Overlays.apply(base, (String) overlay.get().get("yaml"));
            } catch (IllegalArgumentException e) {
                // Stored overlays are validated on write; one that no longer fits a newer app version is skipped.
                LOG.warnf("Overlay of %s for tenant %s ignored: %s", doctype, tenant.get(), e.getMessage());
            }
        }
        cache.put(key, new Cached(def, System.currentTimeMillis()));
        return def;
    }

    /** Effective definitions of every DocType for the tenant in scope. */
    public List<DocTypeDefinition> all() {
        return registry.all().stream().map(d -> effective(d.name())).toList();
    }

    public Optional<Map<String, Object>> get(String doctype) {
        registry.require(doctype);
        return tx.inTenantTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT doctype, yaml, version, updated_by, updated_at FROM astra_overlay WHERE doctype = ?")) {
                ps.setString(1, doctype);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("doctype", rs.getString(1));
                    m.put("yaml", rs.getString(2));
                    m.put("version", rs.getInt(3));
                    m.put("updatedBy", rs.getString(4));
                    m.put("updatedAt", rs.getTimestamp(5).toInstant());
                    m.putAll(Overlays.touchedFields(rs.getString(2)));
                    return Optional.of(m);
                }
            }
        });
    }

    /** DocTypes that have an overlay in the tenant in scope. */
    public List<String> overlaid() {
        return tx.inTenantTx(conn -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT doctype FROM astra_overlay ORDER BY doctype");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    /** Validates the overlay against the app's DocType, stores it for the tenant in scope and applies it at once. */
    public Map<String, Object> put(String doctype, String yaml, String user) {
        DocTypeDefinition base = registry.require(doctype);
        try {
            Overlays.apply(base, yaml);
        } catch (IllegalArgumentException e) {
            throw new DocValidationException(e.getMessage());
        }
        tx.inTenantTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO astra_overlay (tenant_id, doctype, yaml, updated_by) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, doctype) DO UPDATE SET yaml = excluded.yaml, "
                            + "version = astra_overlay.version + 1, updated_by = excluded.updated_by, updated_at = now()")) {
                ps.setString(1, TenantContext.require());
                ps.setString(2, doctype);
                ps.setString(3, yaml);
                ps.setString(4, user);
                ps.executeUpdate();
            }
            return null;
        });
        cache.remove(TenantContext.require() + "|" + doctype);
        return get(doctype).orElseThrow();
    }

    public boolean delete(String doctype) {
        registry.require(doctype);
        boolean removed = tx.inTenantTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM astra_overlay WHERE doctype = ?")) {
                ps.setString(1, doctype);
                return ps.executeUpdate() > 0;
            }
        });
        cache.remove(TenantContext.require() + "|" + doctype);
        return removed;
    }
}
