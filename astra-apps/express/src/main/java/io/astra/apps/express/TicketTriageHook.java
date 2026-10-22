package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Customer care rules: loss or damage complaints are escalated, every ticket gets an SLA deadline from
 * its priority, and the linked waybill's current status is copied in so the agent sees OPS state
 * without leaving CARE. Rule-based today; AI triage over MCP is on the roadmap.
 */
@ApplicationScoped
public class TicketTriageHook implements DocLifecycleHook {

    private static final List<String> ESCALATE = List.of("mất", "thất lạc", "hỏng", "vỡ", "lost", "damaged", "broken");
    private static final Set<String> LOW = Set.of("Low", "Normal");
    private static final Map<String, Duration> SLA = Map.of(
            "Urgent", Duration.ofHours(4),
            "High", Duration.ofHours(24),
            "Normal", Duration.ofHours(72),
            "Low", Duration.ofDays(5));

    @Inject DocumentService docs;

    @Override
    public String doctype() { return "SupportTicket"; }

    @Override
    public void beforeSave(Document t) {
        String text = (Objects.toString(t.get("subject"), "") + " " + Objects.toString(t.get("description"), "")).toLowerCase(Locale.ROOT);
        if (LOW.contains(t.getString("priority"))) {
            ESCALATE.stream().filter(text::contains).findFirst().ifPresent(word -> {
                t.set("priority", "High");
                t.set("triage_note", "auto-escalated: mentions '" + word + "'");
            });
        }
        if (t.get("sla_due") == null) {
            t.set("sla_due", Instant.now().plus(SLA.getOrDefault(t.getString("priority"), SLA.get("Normal"))).toString());
        }
        String waybill = t.getString("waybill");
        if (waybill != null && !waybill.isBlank()) {
            docs.get("Waybill", waybill).ifPresent(w -> t.set("waybill_status", w.getString("ops_status")));
        }
    }
}
