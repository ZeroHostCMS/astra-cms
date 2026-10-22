package io.astra.apps.stms.copilot;

import java.util.List;
import java.util.Map;

/**
 * The language model behind the dispatch copilot: turns a dispatcher's instruction into tool calls. Implementations
 * are CDI beans; {@code astra.stms.llm} selects one by {@link #id()}. Only {@link MockLlmClient} exists today; a real
 * provider plugs in here with the same contract.
 */
public interface LlmClient {

    /** Matches {@code astra.stms.llm}, e.g. {@code mock}. */
    String id();

    /** True when no model is called (scripted answers); the copilot then says so in every response. */
    boolean simulated();

    /**
     * Tool calls for the instruction, in order.
     *
     * @param context facts the model may use, at least {@code plan} (the dispatch plan the dispatcher is looking at)
     */
    List<ToolCall> toolCalls(String instruction, Map<String, Object> context);
}
