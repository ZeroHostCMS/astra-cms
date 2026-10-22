package io.astra.server;

import io.astra.ai.AppToolInvoker;
import io.astra.ai.PiiMasking;
import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.Action;
import io.astra.doctype.store.DocumentStore;
import io.astra.doctype.sync.SyncAwareLsnFilter;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.tenant.TenantContext;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST facade of the STMS dispatch copilot for the Desk and scripts. It runs the app's {@code stms_copilot} tool
 * through the same pipeline as the MCP server ({@link AppToolInvoker}): the caller's tenant, read permission on what
 * the tool reads (so only admin and dispatcher may replan), PII masking, an audit log line, and {@code X-Tx-LSN}
 * after the write. The answer says {@code llm: "mock"} while the language model is the scripted demo stand-in.
 */
@Path("/api/stms")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class StmsCopilotResource {

    static final String COPILOT_TOOL = "stms_copilot";
    static final String PLAN = "DispatchPlan";
    static final String EVENT = "DispatchEvent";

    @Inject AppToolInvoker tools;
    @Inject TenantRequest tenant;
    @Inject DocumentStore store;
    @Inject DocPermissions permissions;
    @Inject PiiMasking pii;

    /** Body {@code {plan, instruction}}: the copilot replans the draft plan and explains what changed. */
    @POST
    @Path("/copilot")
    public Response copilot(Map<String, Object> body) {
        if (body == null) throw new DocValidationException("body must be {\"plan\": …, \"instruction\": …}");
        String plan = text(body, "plan");
        String instruction = text(body, "instruction");
        tools.authorize(COPILOT_TOOL);   // 403 before revealing whether the plan exists
        requirePlan(plan);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("plan", plan);
        args.put("instruction", instruction);
        AppToolInvoker.Outcome outcome = tools.call("REST", COPILOT_TOOL, args);
        Response.ResponseBuilder response = Response.ok(outcome.result());
        if (outcome.wrote()) response.header(SyncAwareLsnFilter.TX_LSN, tools.txLsn());
        return response.build();
    }

    /** The plan's live-change log, newest first: what the copilot and the replanning tools did to it. */
    @GET
    @Path("/plans/{name}/events")
    public Object events(@PathParam("name") String name) {
        permissions.require(PLAN, tenant.roles(), Action.READ);
        permissions.require(EVENT, tenant.roles(), Action.READ);
        requirePlan(name);
        return TenantContext.call(tenant.tenantId(),
                () -> pii.mask(store.list(EVENT, Map.of("plan", name), 200, 0).stream().map(StmsCopilotResource::doc).toList()));
    }

    /** A plan of another tenant is invisible here (row-level security), so it is a 404 like a plan that never existed. */
    private void requirePlan(String name) {
        boolean exists = TenantContext.call(tenant.tenantId(), () -> store.get(PLAN, name).isPresent());
        if (!exists) {
            throw new NotFoundException(Response.status(404)
                    .entity(Map.of("error", "dispatch plan " + name + " not found")).type(MediaType.APPLICATION_JSON).build());
        }
    }

    private static String text(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof String s) || s.isBlank()) throw new DocValidationException("'" + key + "' is required (text)");
        return s.strip();
    }

    private static Map<String, Object> doc(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doctype", d.doctype());
        m.put("name", d.name());
        m.put("owner", d.owner());
        m.put("createdAt", String.valueOf(d.createdAt()));
        m.put("data", d.data());
        return m;
    }
}
