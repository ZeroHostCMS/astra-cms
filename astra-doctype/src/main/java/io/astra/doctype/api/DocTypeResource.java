package io.astra.doctype.api;

import io.astra.doctype.registry.DocTypeOverlays;
import io.astra.doctype.store.ProjectionIndexer;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.tenant.TenantContext;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** Metadata API: what DocTypes exist and what they declare, as the current tenant sees them (overlays applied). */
@Path("/api/doctype")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DocTypeResource {

    @Inject DocTypeOverlays doctypes;
    @Inject ProjectionIndexer projections;
    @Inject TenantRequest tenant;

    @GET
    public List<DocTypeDefinition> list() {
        return TenantContext.call(tenant.tenantId(), () -> doctypes.all());
    }

    @GET
    @Path("/{name}")
    public DocTypeDefinition get(@PathParam("name") String name) {
        return TenantContext.call(tenant.tenantId(), () -> doctypes.effective(name));
    }

    /** The online expression indexes behind this DocType's {@code projected} fields. */
    @GET
    @Path("/{name}/projections")
    public List<Map<String, Object>> projections(@PathParam("name") String name) {
        return projections.status(name);
    }
}
