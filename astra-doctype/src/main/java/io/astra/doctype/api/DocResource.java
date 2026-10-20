package io.astra.doctype.api;

import io.astra.doctype.store.DocumentStore;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.doc.Document;
import io.astra.spi.tenant.TenantContext;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generated document API: one resource serves every DocType.
 * Each handler runs on a virtual thread with the request's tenant bound in the ScopedValue scope.
 */
@Path("/api/doc/{doctype}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DocResource {

    @Inject DocumentStore store;
    @Inject TenantRequest tenant;

    @GET
    public List<Map<String, Object>> list(@PathParam("doctype") String doctype,
                                          @QueryParam("limit") @DefaultValue("20") int limit,
                                          @QueryParam("offset") @DefaultValue("0") int offset) {
        return scoped(() -> store.list(doctype, Math.min(limit, 200), offset).stream().map(DocResource::toJson).toList());
    }

    @GET
    @Path("/{name}")
    public Map<String, Object> get(@PathParam("doctype") String doctype, @PathParam("name") String name) {
        return scoped(() -> store.get(doctype, name).map(DocResource::toJson)
                .orElseThrow(() -> new NotFoundException(doctype + "/" + name)));
    }

    @POST
    public Response create(@PathParam("doctype") String doctype, Map<String, Object> body) {
        Document saved = scoped(() -> store.insert(doctype, body, tenant.userId()));
        return Response.created(URI.create("/api/doc/" + doctype + "/" + saved.name())).entity(toJson(saved)).build();
    }

    @PUT
    @Path("/{name}")
    public Map<String, Object> update(@PathParam("doctype") String doctype, @PathParam("name") String name,
                                      Map<String, Object> patch) {
        return scoped(() -> store.update(doctype, name, patch).map(DocResource::toJson)
                .orElseThrow(() -> new NotFoundException(doctype + "/" + name)));
    }

    /** Binds the request's tenant into the ScopedValue scope for the duration of the kernel call. */
    private <T> T scoped(ScopedValue.CallableOp<T, RuntimeException> work) {
        return TenantContext.call(tenant.tenantId(), work);
    }

    static Map<String, Object> toJson(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doctype", d.doctype());
        m.put("name", d.name());
        m.put("docstatus", d.docstatus());
        m.put("owner", d.owner());
        m.put("createdAt", d.createdAt());
        m.put("modifiedAt", d.modifiedAt());
        m.put("version", d.version());
        m.put("data", d.data());
        return m;
    }
}
