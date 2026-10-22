package io.astra.doctype.api;

import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.Action;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generated document API: one resource serves every DocType.
 * Each handler checks the DocType's permission rules against the caller's roles in the tenant,
 * then runs on a virtual thread with the request's tenant bound in the ScopedValue scope.
 */
@Path("/api/doc/{doctype}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DocResource {

    @Inject DocumentStore store;
    @Inject TenantRequest tenant;
    @Inject DocPermissions permissions;

    /** Lists documents; every query parameter other than {@code limit}/{@code offset} is an equality filter on a field or {@code docstatus}. */
    @GET
    public List<Map<String, Object>> list(@PathParam("doctype") String doctype,
                                          @QueryParam("limit") @DefaultValue("20") int limit,
                                          @QueryParam("offset") @DefaultValue("0") int offset,
                                          @Context UriInfo uri) {
        permissions.require(doctype, tenant.roles(), Action.READ);
        Map<String, String> filters = new LinkedHashMap<>();
        uri.getQueryParameters().forEach((k, v) -> {
            if (!k.equals("limit") && !k.equals("offset") && !v.isEmpty()) filters.put(k, v.getFirst());
        });
        return scoped(() -> store.list(doctype, filters, Math.min(limit, 200), offset).stream().map(DocResource::toJson).toList());
    }

    @GET
    @Path("/{name}")
    public Map<String, Object> get(@PathParam("doctype") String doctype, @PathParam("name") String name) {
        permissions.require(doctype, tenant.roles(), Action.READ);
        return scoped(() -> store.get(doctype, name).map(DocResource::toJson)
                .orElseThrow(() -> new NotFoundException(doctype + "/" + name)));
    }

    @POST
    public Response create(@PathParam("doctype") String doctype, Map<String, Object> body) {
        permissions.require(doctype, tenant.roles(), Action.CREATE);
        Document saved = scoped(() -> store.insert(doctype, body, tenant.userId()));
        return Response.created(URI.create("/api/doc/" + doctype + "/" + saved.name())).entity(toJson(saved)).build();
    }

    @PUT
    @Path("/{name}")
    public Map<String, Object> update(@PathParam("doctype") String doctype, @PathParam("name") String name,
                                      Map<String, Object> patch) {
        return scoped(() -> {
            Document existing = store.get(doctype, name).orElseThrow(() -> new NotFoundException(doctype + "/" + name));
            permissions.requireWrite(doctype, tenant.roles(), tenant.userId(), existing);
            return store.update(doctype, name, patch).map(DocResource::toJson)
                    .orElseThrow(() -> new NotFoundException(doctype + "/" + name));
        });
    }

    @POST
    @Path("/{name}/submit")
    public Map<String, Object> submit(@PathParam("doctype") String doctype, @PathParam("name") String name) {
        store.requireSubmittable(doctype);
        return scoped(() -> {
            Document existing = store.get(doctype, name).orElseThrow(() -> new NotFoundException(doctype + "/" + name));
            permissions.requireSubmit(doctype, tenant.roles(), tenant.userId(), existing);
            return store.submit(doctype, name).map(DocResource::toJson)
                    .orElseThrow(() -> new NotFoundException(doctype + "/" + name));
        });
    }

    @POST
    @Path("/{name}/cancel")
    public Map<String, Object> cancel(@PathParam("doctype") String doctype, @PathParam("name") String name) {
        permissions.require(doctype, tenant.roles(), Action.CANCEL);
        return scoped(() -> store.cancel(doctype, name).map(DocResource::toJson)
                .orElseThrow(() -> new NotFoundException(doctype + "/" + name)));
    }

    @POST
    @Path("/{name}/amend")
    public Response amend(@PathParam("doctype") String doctype, @PathParam("name") String name) {
        permissions.require(doctype, tenant.roles(), Action.CREATE);
        Document draft = scoped(() -> store.amend(doctype, name, tenant.userId())
                .orElseThrow(() -> new NotFoundException(doctype + "/" + name)));
        return Response.created(URI.create("/api/doc/" + doctype + "/" + draft.name())).entity(toJson(draft)).build();
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
        m.put("amendedFrom", d.amendedFrom());
        m.put("data", d.data());
        return m;
    }
}
