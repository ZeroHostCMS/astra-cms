package io.astra.apps.express;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.security.Caller;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool: the anomaly alerts routed to the caller's roles (OPS, accounting, customer care). */
@ApplicationScoped
public class MyAlertsTool implements McpTool {

    @Inject DocumentService docs;
    @Inject Caller caller;

    @Override public String name() { return "my_alerts"; }

    @Override public String description() {
        return "Anomaly alerts assigned to the caller's role (weight mismatches to ops, COD payouts to accountant, failed deliveries to care), newest first.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("status", Map.of("type", "string", "enum", List.of("Open", "Acknowledged", "Dismissed"))));
    }

    @Override public List<String> reads() { return List.of(AnomalyDetector.ALERT); }

    @Override public Object call(Map<String, Object> args) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String role : caller.roles()) {
            Map<String, String> filter = new LinkedHashMap<>(Map.of("assigned_role", role));
            if (args.get("status") != null) filter.put("status", args.get("status").toString());
            for (Document a : docs.find(AnomalyDetector.ALERT, filter, 100)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("alert", a.name());
                for (String f : List.of("kind", "severity", "detail", "ref_name", "status", "raised_by")) m.put(f, a.get(f));
                out.add(m);
            }
        }
        return out;
    }
}
