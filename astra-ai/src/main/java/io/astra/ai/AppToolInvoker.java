package io.astra.ai;

import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.Action;
import io.astra.doctype.sync.SyncWatermark;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.ai.McpTool;
import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Runs an app's {@link McpTool} for the authenticated caller of the current request, whichever endpoint received it
 * (the MCP server, or a REST facade such as the dispatch copilot): binds the caller's tenant, requires read permission
 * on every DocType in {@link McpTool#reads()}, logs user, tenant and tool, and masks PII in the result. Whether the
 * call may have written is reported so the endpoint can answer with {@code X-Tx-LSN}.
 */
@ApplicationScoped
public class AppToolInvoker {

    private static final Logger LOG = Logger.getLogger(AppToolInvoker.class);

    /** What a tool returned (PII already masked) and whether the tool declares that it writes. */
    public record Outcome(Object result, boolean wrote) { }

    @Inject TenantRequest tenant;
    @Inject DocPermissions permissions;
    @Inject PiiMasking pii;
    @Inject SyncWatermark watermark;
    @Inject Instance<McpTool> tools;

    public List<McpTool> all() {
        return tools.stream().toList();
    }

    /** The tool, after checking the caller may read everything it reads; unknown names are an {@link IllegalArgumentException}. */
    public McpTool authorize(String name) {
        McpTool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + name));
        for (String doctype : tool.reads()) permissions.require(doctype, tenant.roles(), Action.READ);
        return tool;
    }

    /**
     * Calls the tool in the caller's tenant.
     *
     * @param channel the endpoint, for the log line, e.g. {@code MCP} or {@code REST}
     */
    public Outcome call(String channel, String name, Map<String, Object> args) {
        McpTool tool = authorize(name);
        LOG.infof("%s tool %s by %s in tenant %s (arguments: %s)", channel, name, tenant.userId(), tenant.tenantId(), args.keySet());
        Object result = TenantContext.call(tenant.tenantId(), () -> pii.mask(tool.call(args)));
        return new Outcome(result, tool.writes());
    }

    /** The WAL position now; read after a committed write it covers that write. */
    public String txLsn() {
        return watermark.currentLsn();
    }
}
