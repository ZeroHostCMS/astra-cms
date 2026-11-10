package io.astra.apps.express;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: replays the last 30 days of events through a draft detector module in the sandbox and scores it against
 * the labels of the planted anomalies in the demo data: alerts per kind, true and false positives, misses.
 */
@ApplicationScoped
public class AnomalyBacktestTool implements McpTool {

    static final int MAX_EVENTS = 3_000;

    @Inject DocumentService docs;
    @Inject ScriptRuntime scripts;

    @Override public String name() { return "anomaly_backtest"; }

    @Override public String description() {
        return "Backtest an anomaly detector (Wasm exporting check(kind, a, b, c, d) -> i64, see docs/overlay-wasm.md) on the tenant's "
                + "events of the last 30 days (up to 3,000), each call under the sandbox deadline. Returns per kind: events, alerts, "
                + "alerts on labelled anomalies (true positives), alerts on unlabelled events (false positives), labelled anomalies missed, "
                + "and example alerts. Nothing is saved.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("module_base64", Map.of("type", "string", "description", "the .wasm module, base64-encoded")),
                "required", List.of("module_base64"));
    }

    @Override public List<String> reads() { return List.of("Waybill", "CODRemittance"); }

    /** At most {@link #MAX_EVENTS}: every COD payout and failed delivery (they are few), then hub scans up to the cap. */
    static List<OpsEvents.Event> capped(List<OpsEvents.Event> events) {
        if (events.size() <= MAX_EVENTS) return events;
        List<OpsEvents.Event> out = new ArrayList<>(events.stream().filter(e -> e.kind() != AnomalyDetector.Kind.WEIGHT).limit(MAX_EVENTS).toList());
        events.stream().filter(e -> e.kind() == AnomalyDetector.Kind.WEIGHT).limit(MAX_EVENTS - out.size()).forEach(out::add);
        return out;
    }

    @Override public Object call(Map<String, Object> args) {
        byte[] module = Base64.getDecoder().decode(String.valueOf(args.get("module_base64")).trim());
        List<OpsEvents.Event> events = capped(OpsEvents.load(docs));
        Map<String, Map<String, Object>> byKind = new LinkedHashMap<>();
        List<String> examples = new ArrayList<>();
        int errors = 0;
        for (OpsEvents.Event e : events) {
            Map<String, Object> k = byKind.computeIfAbsent(e.kind().label, x -> new LinkedHashMap<>(
                    Map.of("events", 0, "alerts", 0, "true_positives", 0, "false_positives", 0, "missed", 0, "labelled", 0)));
            k.merge("events", 1, (x, y) -> (int) x + (int) y);
            if (e.label() != null) k.merge("labelled", 1, (x, y) -> (int) x + (int) y);
            long score;
            try {
                score = scripts.callLong(module, AnomalyDetector.FUNCTION, AnomalyDetector.TIMEOUT_MS, e.kind().code, e.a(), e.b(), e.c(), 0);
            } catch (ScriptException ex) {
                errors++;
                continue;
            }
            if (score > 0) {
                k.merge("alerts", 1, (x, y) -> (int) x + (int) y);
                k.merge(e.label() != null ? "true_positives" : "false_positives", 1, (x, y) -> (int) x + (int) y);
                if (examples.size() < 10) examples.add(e.kind().label + " " + e.ref() + " → " + score + " (" + e.detail() + ")" + (e.label() == null ? " [no label]" : ""));
            } else if (e.label() != null) {
                k.merge("missed", 1, (x, y) -> (int) x + (int) y);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events.size());
        out.put("by_kind", byKind);
        out.put("module_errors", errors);
        out.put("examples", examples);
        return out;
    }
}
