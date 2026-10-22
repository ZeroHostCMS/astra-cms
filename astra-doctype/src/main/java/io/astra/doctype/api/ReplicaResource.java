package io.astra.doctype.api;

import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.Action;
import io.astra.doctype.store.TenantTransactions;
import io.astra.doctype.sync.SyncWatermark;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.tenant.TenantContext;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to the replica tables kept by the CDC worker. Each replica is readable by whoever may
 * read its source DocType ({@code astra.cdc.replicas}); rows are tenant-isolated by row-level security.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class ReplicaResource {

    @Inject TenantTransactions tx;
    @Inject TenantRequest tenant;
    @Inject DocPermissions permissions;
    @Inject SyncWatermark watermark;

    /** replica table → source DocType, e.g. {@code replica_waybill:Waybill,replica_order:CustomerOrder}. */
    @ConfigProperty(name = "astra.cdc.replicas", defaultValue = "replica_waybill:Waybill,replica_order:CustomerOrder")
    List<String> replicas;

    @GET
    @Path("/replica/{table}")
    public Map<String, Object> rows(@PathParam("table") String table, @QueryParam("name") String name,
                                    @QueryParam("limit") @DefaultValue("50") int limit) {
        String source = replicas.stream().filter(r -> r.startsWith(table + ":")).map(r -> r.substring(table.length() + 1))
                .findFirst().orElseThrow(() -> new NotFoundException("no replica " + table));
        permissions.require(source, tenant.roles(), Action.READ);
        List<Map<String, Object>> rows = TenantContext.call(tenant.tenantId(), () -> tx.inTenantTx(conn -> {
            // The table name comes from configuration, never from the request.
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + table
                    + " WHERE (?::text IS NULL OR name = ?) ORDER BY synced_at DESC LIMIT ?")) {
                ps.setString(1, name);
                ps.setString(2, name);
                ps.setInt(3, Math.min(Math.max(limit, 1), 200));
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 1; c <= n; c++) {
                            Object v = rs.getObject(c);
                            row.put(rs.getMetaData().getColumnLabel(c), v instanceof java.sql.Timestamp t ? t.toInstant().toString()
                                    : v instanceof org.postgresql.util.PGobject o ? o.getValue() : v);
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        }));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", table);
        m.put("source", source);
        m.put("sync", watermark.status());
        m.put("rows", rows);
        return m;
    }

    @GET
    @Path("/sync/status")
    public Map<String, Object> status() {
        return watermark.status();
    }
}
