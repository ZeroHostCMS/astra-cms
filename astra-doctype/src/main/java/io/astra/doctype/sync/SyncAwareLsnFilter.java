package io.astra.doctype.sync;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sync-Aware LSN. Successful writes carry {@code X-Tx-LSN}. Reads from replicas that send
 * {@code X-Required-LSN} get {@code 202 Sync Pending} until the CDC watermark has caught up, so a client
 * that just wrote in one module never reads stale data in another, without anyone waiting on a lock.
 */
@Provider
public class SyncAwareLsnFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String TX_LSN = "X-Tx-LSN";
    public static final String REQUIRED_LSN = "X-Required-LSN";
    private static final Set<String> WRITES = Set.of("POST", "PUT", "DELETE", "PATCH");

    @Inject SyncWatermark watermark;

    /** Request path without the leading slash, e.g. {@code api/doc/Article}. */
    private static String path(ContainerRequestContext req) {
        String p = req.getUriInfo().getPath();
        return p.startsWith("/") ? p.substring(1) : p;
    }

    @Override
    public void filter(ContainerRequestContext req) {
        String required = req.getHeaderString(REQUIRED_LSN);
        if (required == null || required.isBlank() || !path(req).startsWith("api/replica")) return;
        if (!required.matches("[0-9A-Fa-f]{1,8}/[0-9A-Fa-f]{1,8}")) {
            req.abortWith(Response.status(400).entity(Map.of("error", "malformed " + REQUIRED_LSN)).build());
            return;
        }
        if (!watermark.reached(required)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "sync-pending");
            body.put("requiredLsn", required);
            body.put("replicatedLsn", watermark.status().get("replicatedLsn"));
            body.put("retryAfterMs", 50);
            req.abortWith(Response.status(202).entity(body).build());
        }
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        String path = path(req);
        if (WRITES.contains(req.getMethod()) && res.getStatus() / 100 == 2 && (path.startsWith("api/doc/") || path.equals("mcp"))) {
            res.getHeaders().putSingle(TX_LSN, watermark.currentLsn());
        }
    }
}
