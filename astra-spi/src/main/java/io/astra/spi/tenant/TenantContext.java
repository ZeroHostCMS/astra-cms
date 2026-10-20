package io.astra.spi.tenant;

import java.util.Optional;

/**
 * The current tenant, carried in a Java 25 {@link ScopedValue}. The kernel binds it once per request;
 * every kernel service reads it from here. No kernel API accepts a tenant as a caller-supplied
 * parameter, which is what makes cross-tenant access impossible from app code.
 */
public final class TenantContext {

    private static final ScopedValue<String> TENANT = ScopedValue.newInstance();

    private TenantContext() { }

    /** Runs {@code task} with the given tenant bound for the duration of the call and all child tasks. */
    public static <T, X extends Throwable> T call(String tenantId, ScopedValue.CallableOp<T, X> task) throws X {
        return ScopedValue.where(TENANT, requireValid(tenantId)).call(task);
    }

    public static void run(String tenantId, Runnable task) {
        ScopedValue.where(TENANT, requireValid(tenantId)).run(task);
    }

    /** The bound tenant, or empty when called outside a tenant scope (for example a startup job). */
    public static Optional<String> current() {
        return TENANT.isBound() ? Optional.of(TENANT.get()) : Optional.empty();
    }

    /** The bound tenant; throws when no tenant is in scope, which is always a programming error. */
    public static String require() {
        if (!TENANT.isBound()) {
            throw new IllegalStateException("No tenant bound in the current scope");
        }
        return TENANT.get();
    }

    private static String requireValid(String tenantId) {
        if (tenantId == null || !tenantId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("Invalid tenant id: " + tenantId);
        }
        return tenantId;
    }
}
