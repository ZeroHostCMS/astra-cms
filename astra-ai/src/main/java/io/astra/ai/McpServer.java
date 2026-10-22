package io.astra.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.astra.doctype.ledger.LedgerStore;
import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.AccessDeniedException;
import io.astra.doctype.permission.DocPermissions.Action;
import io.astra.doctype.registry.DocTypeOverlays;
import io.astra.doctype.registry.DocTypeRegistry.DocTypeNotFoundException;
import io.astra.doctype.store.DocumentStore;
import io.astra.doctype.sync.SyncAwareLsnFilter;
import io.astra.doctype.sync.SyncWatermark;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.tenant.TenantContext;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model Context Protocol server (Streamable HTTP transport, JSON responses) at {@code /mcp}. An agent
 * connects with the same Keycloak bearer token and {@code X-Astra-Tenant} header as any other client, so
 * every tool runs under the caller's tenant, row-level security and DocType permissions. Results pass
 * through {@link PiiMasking}; every call is logged with user, tenant and tool.
 */
@Path("/mcp")
@RunOnVirtualThread
public class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class);
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    /** Kernel tools that write; app tools only read the DocTypes they declare in {@link McpTool#reads()}. */
    static final Set<String> WRITE_TOOLS = Set.of("create_document");

    @Inject TenantRequest tenant;
    @Inject DocumentStore store;
    @Inject DocTypeOverlays doctypes;
    @Inject DocPermissions permissions;
    @Inject LedgerStore ledger;
    @Inject PiiMasking pii;
    @Inject SyncWatermark watermark;
    @Inject Instance<McpTool> appTools;
    @Inject ObjectMapper json;

    @ConfigProperty(name = "astra.ledger.account-doctype", defaultValue = "Account")
    String accountDoctype;

    /** No server-initiated stream: the spec allows 405 for clients that try to open one. */
    @GET
    public Response stream() {
        return Response.status(405).header("Allow", "POST").build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handle(Map<String, Object> message) {
        Object id = message.get("id");
        String method = String.valueOf(message.get("method"));
        if (id == null) return Response.accepted().build(); // notifications, e.g. notifications/initialized
        @SuppressWarnings("unchecked")
        Map<String, Object> params = message.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        return switch (method) {
            case "initialize" -> ok(id, initialize(params));
            case "ping" -> ok(id, Map.of());
            case "tools/list" -> ok(id, Map.of("tools", tools()));
            case "tools/call" -> toolCall(id, params);
            default -> Response.ok(Map.of("jsonrpc", "2.0", "id", id,
                    "error", Map.of("code", -32601, "message", "method not found: " + method))).build();
        };
    }

    /**
     * Every MCP call is a POST, so the response carries {@code X-Tx-LSN} only when the call committed a write
     * (a successful {@code create_document}); reads would otherwise hand the agent a position it never wrote.
     */
    private Response toolCall(Object id, Map<String, Object> params) {
        Map<String, Object> result = TenantContext.call(tenant.tenantId(), () -> call(params));
        Response.ResponseBuilder response = Response.ok(envelope(id, result));
        if (WRITE_TOOLS.contains(String.valueOf(params.get("name"))) && Boolean.FALSE.equals(result.get("isError"))) {
            response.header(SyncAwareLsnFilter.TX_LSN, watermark.currentLsn());
        }
        return response.build();
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String requested = String.valueOf(params.get("protocolVersion"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst());
        m.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        m.put("serverInfo", Map.of("name", "astra", "title", "Astra (" + tenant.tenantId() + ")", "version", "0.1.0"));
        m.put("instructions", "Business documents of tenant " + tenant.tenantId() + " on the Astra platform. "
                + "Call list_doctypes first to see what exists and which fields each DocType has. "
                + "Personal data fields are masked. You act with the signed-in user's permissions.");
        return m;
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("list_doctypes", "List the DocTypes (business record types) this user can read, with their fields.",
                Map.of(), List.of()));
        out.add(tool("search_documents", "Find documents of a DocType whose fields equal the given values, newest first. "
                        + "Filters may also use docstatus (0 draft, 1 submitted, 2 cancelled).",
                Map.of("doctype", str("DocType name, e.g. CustomerOrder"),
                        "filters", Map.of("type", "object", "description", "field → exact value", "additionalProperties", Map.of("type", "string")),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 10)),
                List.of("doctype")));
        out.add(tool("get_document", "Read one document by DocType and name.",
                Map.of("doctype", str("DocType name"), "name", str("document name, e.g. ORD-20261021-00001")), List.of("doctype", "name")));
        out.add(tool("create_document", "Create a draft document. The kernel validates it and runs the app's rules.",
                Map.of("doctype", str("DocType name"), "data", Map.of("type", "object", "description", "field values")),
                List.of("doctype", "data")));
        out.add(tool("ledger_trial_balance", "Debit, credit and balance per account in the general ledger.", Map.of(), List.of()));
        for (McpTool t : appTools) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("inputSchema", t.inputSchema());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> call(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        LOG.infof("MCP tool %s by %s in tenant %s (arguments: %s)", name, tenant.userId(), tenant.tenantId(), args.keySet());
        try {
            Object result = switch (name) {
                case "list_doctypes" -> listDoctypes();
                case "search_documents" -> search(args);
                case "get_document" -> get(args);
                case "create_document" -> create(args);
                case "ledger_trial_balance" -> {
                    permissions.require(accountDoctype, tenant.roles(), Action.READ);
                    yield ledger.trialBalance();
                }
                default -> appTool(name, args);
            };
            return toolResult(pii.mask(result), false);
        } catch (AccessDeniedException | DocValidationException | DocTypeNotFoundException | IllegalArgumentException e) {
            return toolResult(Map.of("error", e.getMessage()), true);
        }
    }

    private Object appTool(String name, Map<String, Object> args) {
        for (McpTool t : appTools) {
            if (!t.name().equals(name)) continue;
            for (String doctype : t.reads()) permissions.require(doctype, tenant.roles(), Action.READ);
            return t.call(args);
        }
        throw new IllegalArgumentException("unknown tool: " + name);
    }

    private List<Map<String, Object>> listDoctypes() {
        Set<String> roles = tenant.roles();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocTypeDefinition def : doctypes.all()) {
            if (!permissions.effective(roles).get(def.name()).read()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", def.name());
            m.put("module", def.module());
            m.put("kind", def.kind());
            m.put("submittable", def.submittable());
            m.put("fields", def.fields().stream().map(f -> {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.name());
                fm.put("type", f.type().name().toLowerCase());
                fm.put("label", f.label());
                if (f.required()) fm.put("required", true);
                if (!f.options().isEmpty()) fm.put("options", f.options());
                if (f.pii()) fm.put("pii", true);
                return fm;
            }).toList());
            out.add(m);
        }
        return out;
    }

    private Object search(Map<String, Object> args) {
        String doctype = required(args, "doctype");
        permissions.require(doctype, tenant.roles(), Action.READ);
        Map<String, String> filters = new LinkedHashMap<>();
        if (args.get("filters") instanceof Map<?, ?> f) f.forEach((k, v) -> filters.put(String.valueOf(k), String.valueOf(v)));
        int limit = args.get("limit") instanceof Number n ? Math.max(1, Math.min(50, n.intValue())) : 10;
        return store.list(doctype, filters, limit, 0).stream().map(McpServer::doc).toList();
    }

    private Object get(Map<String, Object> args) {
        String doctype = required(args, "doctype");
        permissions.require(doctype, tenant.roles(), Action.READ);
        String name = required(args, "name");
        return store.get(doctype, name).map(McpServer::doc)
                .orElseThrow(() -> new IllegalArgumentException(doctype + " " + name + " not found"));
    }

    private Object create(Map<String, Object> args) {
        String doctype = required(args, "doctype");
        permissions.require(doctype, tenant.roles(), Action.CREATE);
        if (!(args.get("data") instanceof Map<?, ?> data)) throw new IllegalArgumentException("'data' must be an object");
        Map<String, Object> fields = new LinkedHashMap<>();
        data.forEach((k, v) -> fields.put(String.valueOf(k), v));
        return doc(store.insert(doctype, fields, tenant.userId()));
    }

    static Map<String, Object> doc(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doctype", d.doctype());
        m.put("name", d.name());
        m.put("docstatus", d.docstatus());
        m.put("modifiedAt", String.valueOf(d.modifiedAt()));
        m.put("data", d.data());
        return m;
    }

    private Map<String, Object> toolResult(Object result, boolean error) {
        String text;
        try {
            text = json.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            text = String.valueOf(result);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", text)));
        m.put("structuredContent", result instanceof Map<?, ?> ? result : Map.of("items", result));
        m.put("isError", error);
        return m;
    }

    private static Response ok(Object id, Object result) {
        return Response.ok(envelope(id, result)).build();
    }

    private static Map<String, Object> envelope(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", schema);
        return m;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static String required(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isBlank()) throw new IllegalArgumentException("'" + key + "' is required");
        return v.toString();
    }
}
