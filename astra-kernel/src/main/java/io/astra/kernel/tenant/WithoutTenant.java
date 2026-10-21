package io.astra.kernel.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource class or method that is not tenant-scoped (identity endpoints such as "who am I"
 * or "join a tenant"). {@link TenantResolverFilter} skips tenant resolution for it; the endpoint must
 * authorise on its own and must never touch tenant data.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface WithoutTenant { }
