package io.astra.doctype.api;

import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.spi.doctype.DocTypeDefinition;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** Metadata API: what DocTypes exist and what they declare. */
@Path("/api/doctype")
@Produces(MediaType.APPLICATION_JSON)
public class DocTypeResource {

    @Inject DocTypeRegistry registry;

    @GET
    public List<DocTypeDefinition> list() {
        return List.copyOf(registry.all());
    }

    @GET
    @Path("/{name}")
    public DocTypeDefinition get(@PathParam("name") String name) {
        return registry.require(name);
    }
}
