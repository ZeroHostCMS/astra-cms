package io.astra.doctype.api;

import io.astra.doctype.permission.DocPermissions.AccessDeniedException;
import io.astra.doctype.registry.DocTypeOverlays;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.tenant.TenantContext;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Per-tenant DocType overlays. Every member may read them (they shape the forms they use); only the
 * tenant's {@code admin} role may change them. Bodies are YAML ({@code application/yaml} or text).
 */
@Path("/api/overlay")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class OverlayResource {

    static final String ADMIN_ROLE = "admin";

    @Inject DocTypeOverlays overlays;
    @Inject TenantRequest tenant;

    @GET
    public List<String> list() {
        return scoped(() -> overlays.overlaid());
    }

    @GET
    @Path("/{doctype}")
    public Map<String, Object> get(@PathParam("doctype") String doctype) {
        return scoped(() -> overlays.get(doctype).orElseThrow(() -> new NotFoundException("no overlay for " + doctype)));
    }

    @PUT
    @Path("/{doctype}")
    @Consumes({"application/yaml", "text/yaml", MediaType.TEXT_PLAIN})
    public Map<String, Object> put(@PathParam("doctype") String doctype, String yaml) {
        requireAdmin("change the overlay of " + doctype);
        return scoped(() -> overlays.put(doctype, yaml, tenant.userId()));
    }

    @DELETE
    @Path("/{doctype}")
    public Response delete(@PathParam("doctype") String doctype) {
        requireAdmin("remove the overlay of " + doctype);
        return scoped(() -> overlays.delete(doctype)) ? Response.noContent().build() : Response.status(404).build();
    }

    private void requireAdmin(String what) {
        if (!tenant.roles().contains(ADMIN_ROLE)) throw new AccessDeniedException(what);
    }

    private <T> T scoped(ScopedValue.CallableOp<T, RuntimeException> work) {
        return TenantContext.call(tenant.tenantId(), work);
    }
}
