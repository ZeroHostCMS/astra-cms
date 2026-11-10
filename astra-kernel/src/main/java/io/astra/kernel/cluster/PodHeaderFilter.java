package io.astra.kernel.cluster;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Every response says which pod served it ({@code X-Astra-Pod}), so a client watching a canary sees both versions. */
@Provider
public class PodHeaderFilter implements ContainerResponseFilter {

    public static final String HEADER = "X-Astra-Pod";

    @Inject PodIdentity pod;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle(HEADER, pod.name());
    }
}
