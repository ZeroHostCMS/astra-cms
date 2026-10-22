package io.astra.spi.ai;

import java.util.List;
import java.util.Map;

/**
 * Extension point: a tool an app offers to AI agents through the kernel's MCP server. The kernel
 * authenticates the caller, binds the tenant, checks {@link #reads()} against the caller's DocType
 * permissions, logs the call, and masks fields marked {@code pii} in what the tool returns.
 */
public interface McpTool {

    /** Tool name, snake_case, unique across apps, for example {@code track_waybill}. */
    String name();

    /** What the tool does, written for the model that decides when to call it. */
    String description();

    /** JSON Schema of the arguments object. */
    Map<String, Object> inputSchema();

    /** DocTypes the tool reads; the caller needs read permission on all of them. */
    List<String> reads();

    /**
     * Whether a successful call may commit a write. The kernel then answers with {@code X-Tx-LSN}, the WAL position
     * after the call, so the client can demand read-your-writes from replicas. Read-only tools keep the default.
     */
    default boolean writes() {
        return false;
    }

    /**
     * Runs the tool in the caller's tenant. Return plain maps and lists; documents should be returned
     * as {@code {doctype, name, data}} maps so that their PII fields can be masked.
     */
    Object call(Map<String, Object> arguments);
}
