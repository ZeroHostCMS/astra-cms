package io.astra.pluginhost.rollout;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.release.ReleaseInsight;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool: where a release is in its rollout and how healthy it is, plus what the slot's app measures about it. */
@ApplicationScoped
public class ReleaseStatusTool implements McpTool {

    @Inject DocumentService docs;
    @Inject Instance<ReleaseInsight> insights;

    @Override public String name() { return "release_status"; }

    @Override public String description() {
        return "Status of releases: stage (Draft, Canary, Live, Rolled back, Retired), pods, calls, errors, p99 latency and the reason "
                + "of the last stage change, plus slot-specific results (for ops.anomaly: alerts raised and dismissed per kind). "
                + "Give a release name, or a slot for its five newest releases.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "release", Map.of("type", "string", "description", "release name, e.g. REL-00012"),
                "slot", Map.of("type", "string", "description", "slot, e.g. ops.anomaly")));
    }

    @Override public List<String> reads() { return List.of(ReleaseStore.RELEASE); }

    @Override public Object call(Map<String, Object> args) {
        List<Document> releases = args.get("release") != null
                ? docs.get(ReleaseStore.RELEASE, args.get("release").toString()).stream().toList()
                : docs.find(ReleaseStore.RELEASE, args.get("slot") != null ? Map.of("slot", args.get("slot").toString()) : Map.of(), 5);
        return releases.stream().map(this::status).toList();
    }

    private Map<String, Object> status(Document r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("release", r.name());
        for (String f : List.of("slot", "tier", "app_id", "version", "customer", "generator", "stage", "canary_pods", "target_pods",
                "calls", "errors", "p99_ms", "stage_reason", "self_test")) {
            if (r.get(f) != null && !"".equals(r.get(f))) m.put(f, r.get(f));
        }
        m.put("approved", r.docstatus() == 1);
        for (ReleaseInsight insight : insights) {
            if (insight.slot().equals(r.getString("slot"))) m.put("results", insight.results(r.getString("app_id"), r.getString("version")));
        }
        return m;
    }
}
