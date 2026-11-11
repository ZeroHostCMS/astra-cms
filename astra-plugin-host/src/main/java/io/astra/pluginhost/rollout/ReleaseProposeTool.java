package io.astra.pluginhost.rollout;

import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.Action;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: proposes a release as a draft {@code AppRelease}. Saving runs the same vetting as the Desk (load,
 * self-test, SHA-256) and records {@code generator} from the caller's token. Approval is a separate submit that
 * needs the admin role; there is deliberately no MCP tool for it.
 */
@ApplicationScoped
public class ReleaseProposeTool implements McpTool {

    @Inject DocumentService docs;
    @Inject DocPermissions permissions;
    @Inject TenantRequest caller;

    @Override public String name() { return "release_propose"; }

    @Override public String description() {
        return "Propose a new release of a run-time extension (a Wasm module, a rule set or a Tier 1 plugin jar) for one slot, "
                + "as a draft that a person with the admin role must approve on the Desk. The artifact is vetted on save "
                + "(loaded, self-tested); the answer says whether it passed. You cannot approve or publish it yourself.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("slot", Map.of("type", "string", "description", "extension slot, e.g. ops.anomaly or pricing.customer"));
        props.put("tier", Map.of("type", "string", "enum", List.of("wasm", "rules", "isolated-jvm")));
        props.put("app_id", Map.of("type", "string", "description", "name of the module, e.g. anomaly-hub-weight"));
        props.put("version", Map.of("type", "string", "description", "semantic version, e.g. 1.0.0"));
        props.put("artifact_base64", Map.of("type", "string", "description", "the .wasm, .drl or .jar file, base64-encoded"));
        props.put("customer", Map.of("type", "string", "description", "customer code when the release is for one customer only"));
        props.put("notes", Map.of("type", "string", "description", "what changed and why, and the backtest result"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("slot", "tier", "app_id", "version", "artifact_base64"));
    }

    @Override public List<String> reads() { return List.of(ReleaseStore.RELEASE); }

    @Override public boolean writes() { return true; }

    @Override public Object call(Map<String, Object> args) {
        permissions.require(ReleaseStore.RELEASE, caller.roles(), Action.CREATE);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : List.of("slot", "tier", "app_id", "version", "customer", "notes")) {
            if (args.get(k) != null) data.put(k, args.get(k).toString());
        }
        data.put("artifact", String.valueOf(args.get("artifact_base64")));
        Document d = docs.insert(ReleaseStore.RELEASE, data, caller.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("release", d.name());
        out.put("stage", d.getString("stage"));
        out.put("generator", d.getString("generator"));
        out.put("artifact_sha256", d.getString("artifact_sha256"));
        out.put("self_test", d.getString("self_test"));
        out.put("next", "A person with the admin role approves it on the Desk (AppRelease " + d.name() + " → Submit).");
        return out;
    }
}
