package io.astra.kernel.tenant;

import io.astra.kernel.security.Memberships;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the tenant for every request in a fixed order: explicit header, then host subdomain,
 * then the user's only tenant membership, then the configured default (single-tenant mode).
 * The caller must be authenticated and a member of the resolved tenant; otherwise the request is
 * rejected before reaching any resource. Identity comes only from the verified bearer token.
 */
@Provider
public class TenantResolverFilter implements ContainerRequestFilter {

    public static final String TENANT_HEADER = "X-Astra-Tenant";

    @Inject
    TenantRequest tenantRequest;

    @Inject
    SecurityIdentity identity;

    @Context
    ResourceInfo resourceInfo;

    @ConfigProperty(name = "astra.tenancy.mode", defaultValue = "multi")
    String mode;

    @ConfigProperty(name = "astra.tenancy.default-tenant", defaultValue = "default")
    String defaultTenant;

    @ConfigProperty(name = "astra.tenancy.base-domain")
    Optional<String> baseDomain;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (ctx.getUriInfo().getPath().startsWith("q/") || withoutTenant()) {
            return; // health and identity endpoints are not tenant-scoped
        }
        if (identity.isAnonymous()) {
            ctx.abortWith(error(Response.Status.UNAUTHORIZED, "authentication required"));
            return;
        }

        Memberships memberships = Memberships.of(identity);
        String tenant = fromHeader(ctx).or(() -> fromHost(ctx)).or(() -> onlyMembership(memberships)).orElse(null);
        if (tenant == null && "single".equals(mode)) {
            tenant = defaultTenant;
        }
        if (tenant == null || !tenant.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            ctx.abortWith(error(Response.Status.BAD_REQUEST, "tenant could not be resolved; send "
                    + TENANT_HEADER + " or use a tenant subdomain"));
            return;
        }
        Set<String> roles = memberships.roles(tenant);
        if (roles.isEmpty()) {
            ctx.abortWith(error(Response.Status.FORBIDDEN, "not a member of tenant " + tenant));
            return;
        }
        tenantRequest.tenantId(tenant);
        tenantRequest.userId(identity.getPrincipal().getName());
        tenantRequest.roles(roles);
    }

    private boolean withoutTenant() {
        return resourceInfo != null
                && (resourceInfo.getResourceMethod() != null && resourceInfo.getResourceMethod().isAnnotationPresent(WithoutTenant.class)
                    || resourceInfo.getResourceClass() != null && resourceInfo.getResourceClass().isAnnotationPresent(WithoutTenant.class));
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

    private Optional<String> fromHeader(ContainerRequestContext ctx) {
        return Optional.ofNullable(ctx.getHeaderString(TENANT_HEADER)).map(String::trim).filter(s -> !s.isEmpty());
    }

    /** {@code acme.astra.example} with base domain {@code astra.example} resolves to {@code acme}. */
    private Optional<String> fromHost(ContainerRequestContext ctx) {
        if (baseDomain.isEmpty()) return Optional.empty();
        String host = ctx.getUriInfo().getRequestUri().getHost();
        String suffix = "." + baseDomain.get();
        if (host != null && host.endsWith(suffix)) {
            String label = host.substring(0, host.length() - suffix.length());
            if (!label.contains(".")) return Optional.of(label);
        }
        return Optional.empty();
    }

    /** A user who belongs to exactly one tenant does not need to name it. */
    private static Optional<String> onlyMembership(Memberships memberships) {
        return memberships.byTenant().size() == 1
                ? Optional.of(memberships.byTenant().keySet().iterator().next())
                : Optional.empty();
    }
}
