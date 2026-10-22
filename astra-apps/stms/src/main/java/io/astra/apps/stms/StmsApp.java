package io.astra.apps.stms;

import io.astra.spi.app.AstraApp;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Astra STMS: vehicle routing for the courier's hubs (docs/use-case-01.md, module 6). Dispatch plans are solved
 * with Timefold under capacity and time-window constraints, replanned on live events, and exposed to AI agents as
 * MCP tools, through the public SPI only.
 */
@ApplicationScoped
public class StmsApp implements AstraApp {

    @Override
    public String id() { return "io.astra.stms"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public List<String> doctypeResources() {
        return List.of(
                "META-INF/astra/doctypes/hub.doctype.yaml",
                "META-INF/astra/doctypes/vehicle.doctype.yaml",
                "META-INF/astra/doctypes/delivery-stop.doctype.yaml",
                "META-INF/astra/doctypes/dispatch-plan.doctype.yaml",
                "META-INF/astra/doctypes/dispatch-event.doctype.yaml");
    }
}
