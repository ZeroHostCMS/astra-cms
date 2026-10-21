package io.astra.apps.express;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool: where a parcel is, how it got there, the order behind it and any complaints about it. */
@ApplicationScoped
public class TrackWaybillTool implements McpTool {

    @Inject DocumentService docs;

    @Override public String name() { return "track_waybill"; }

    @Override public String description() {
        return "Track a parcel by waybill number (WB-…): current status and hub, every scan with time, "
                + "the customer order behind it, and support tickets that mention it.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "required", List.of("waybill"),
                "properties", Map.of("waybill", Map.of("type", "string", "description", "waybill number, e.g. WB-2610-000001")));
    }

    @Override public List<String> reads() { return List.of("Waybill", "CustomerOrder", "SupportTicket"); }

    @Override public Object call(Map<String, Object> arguments) {
        String name = String.valueOf(arguments.get("waybill"));
        Document waybill = docs.get("Waybill", name).orElseThrow(() -> new IllegalArgumentException("waybill " + name + " not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waybill", doc(waybill));
        docs.get("CustomerOrder", waybill.getString("order")).ifPresent(o -> out.put("order", doc(o)));
        out.put("tickets", docs.find("SupportTicket", Map.of("waybill", name), 20).stream().map(TrackWaybillTool::doc).toList());
        return out;
    }

    static Map<String, Object> doc(Document d) {
        return Map.of("doctype", d.doctype(), "name", d.name(), "docstatus", d.docstatus(), "data", d.data());
    }
}
