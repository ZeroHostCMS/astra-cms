package io.astra.apps.stms.copilot;

import io.astra.apps.stms.Replanner;
import io.astra.apps.stms.Values;
import io.astra.spi.ai.McpTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** MCP tool: the dispatch copilot, so any MCP client (or the demo script) can hand it an instruction. */
@ApplicationScoped
public class CopilotTool implements McpTool {

    @Inject DispatchCopilot copilot;

    @Override public String name() { return "stms_copilot"; }

    @Override public String description() {
        return "Dispatch copilot: give a Vietnamese instruction about a draft dispatch plan (vehicle breakdown, urgent order, "
                + "congestion, time window change, priorities) and it replans through the STMS tools and explains the result. "
                + "The response field 'llm' names the model; 'mock' means scripted demo answers, not a language model.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "required", List.of("plan", "instruction"), "properties", Map.of(
                "plan", Map.of("type", "string", "description", "dispatch plan name, e.g. DP-2610-0001"),
                "instruction", Map.of("type", "string", "description",
                        "e.g. 'Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác, ưu tiên khách VIP trước 10h'")));
    }

    @Override public List<String> reads() { return Replanner.REPLAN_READS; }

    @Override public boolean writes() { return true; }

    @Override public Object call(Map<String, Object> arguments) {
        return copilot.run(Values.text(arguments, "plan"), Values.text(arguments, "instruction"));
    }
}
