package io.astra.doctype.api;

import io.astra.kernel.app.AppRegistry;
import io.astra.doctype.registry.DocTypeRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed apps and the DocTypes each one contributes. */
@Path("/api/app")
@Produces(MediaType.APPLICATION_JSON)
public class AppResource {

    @Inject AppRegistry apps;
    @Inject DocTypeRegistry registry;

    @GET
    public List<Map<String, Object>> list() {
        return apps.apps().stream().map(app -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", app.id());
            m.put("version", app.version());
            m.put("tier", "0 (shared, build-time)");
            m.put("doctypes", registry.namesByApp(app.id()));
            return m;
        }).toList();
    }
}
