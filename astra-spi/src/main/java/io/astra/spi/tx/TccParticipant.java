package io.astra.spi.tx;

import java.util.Map;

/**
 * Try-Confirm-Cancel resource contributed by an app. {@link #prepare} reserves (and may refuse by
 * throwing, leaving the undo to {@link #rollback}); exactly one of {@link #commit} or {@link #rollback}
 * follows once the global transaction ends. Phase 2 may be retried, and a rollback may arrive for a branch
 * whose prepare never ran, so all three must be keyed by {@code branchId}.
 */
public interface TccParticipant {

    String name();

    void prepare(String branchId, String key, Map<String, Object> arguments);

    void commit(String branchId, String key, Map<String, Object> arguments);

    void rollback(String branchId, String key, Map<String, Object> arguments);
}
