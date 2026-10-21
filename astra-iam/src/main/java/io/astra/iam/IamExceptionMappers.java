package io.astra.iam;

import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/** Maps IAM failures to stable JSON error bodies. */
public class IamExceptionMappers {

    @ServerExceptionMapper
    public Response iam(IamException e) {
        return Response.status(e.status()).entity(Map.of("error", e.getMessage())).build();
    }
}
