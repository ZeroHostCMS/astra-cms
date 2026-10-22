package io.astra.apps.stms.copilot;

import java.util.LinkedHashMap;
import java.util.Map;

/** One tool call decided by the model, in the shape of a Claude {@code tool_use} content block. */
public record ToolCall(String id, String name, Map<String, Object> input) {

    public ToolCall {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    public Map<String, Object> toBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "tool_use");
        m.put("id", id);
        m.put("name", name);
        m.put("input", input);
        return m;
    }
}
