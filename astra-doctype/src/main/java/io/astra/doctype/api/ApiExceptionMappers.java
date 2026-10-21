package io.astra.doctype.api;

import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.spi.doc.DocValidationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/** Maps kernel exceptions to stable JSON error bodies. */
public class ApiExceptionMappers {

    @ServerExceptionMapper
    public Response validation(DocValidationException e) {
        return Response.status(422).entity(Map.of("error", "validation", "errors", e.errors())).build();
    }

    @ServerExceptionMapper
    public Response unknownDoctype(DocTypeRegistry.DocTypeNotFoundException e) {
        return Response.status(404).entity(Map.of("error", e.getMessage())).build();
    }

    @ServerExceptionMapper
    public Response accessDenied(DocPermissions.AccessDeniedException e) {
        return Response.status(403).entity(Map.of("error", e.getMessage())).build();
    }
}
