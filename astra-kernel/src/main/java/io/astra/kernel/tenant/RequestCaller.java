package io.astra.kernel.tenant;

import io.astra.spi.security.Caller;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/** {@link Caller} from the current request's {@link TenantRequest}; nobody outside a request (jobs, startup). */
@ApplicationScoped
public class RequestCaller implements Caller {

    @Override
    public String userId() {
        TenantRequest r = request();
        return r == null ? null : r.userId();
    }

    @Override
    public Set<String> roles() {
        TenantRequest r = request();
        return r == null ? Set.of() : r.roles();
    }

    private static TenantRequest request() {
        return Arc.container().requestContext().isActive() ? Arc.container().instance(TenantRequest.class).get() : null;
    }
}
