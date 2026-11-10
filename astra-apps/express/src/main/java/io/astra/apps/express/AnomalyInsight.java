package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.release.ReleaseInsight;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/** For {@code release_status} on slot {@code ops.anomaly}: what one detector version raised, and how much of it people dismissed. */
@ApplicationScoped
public class AnomalyInsight implements ReleaseInsight {

    @Inject DocumentService docs;

    @Override public String slot() { return AnomalyDetector.SLOT; }

    @Override public Map<String, Object> results(String app, String version) {
        Map<String, Map<String, Object>> byKind = new LinkedHashMap<>();
        String prefix = app + "@" + version + " on ";
        for (Document a : docs.find(AnomalyDetector.ALERT, Map.of(), 5_000)) {
            String raisedBy = a.getString("raised_by");
            if (raisedBy == null || !raisedBy.startsWith(prefix)) continue;
            Map<String, Object> k = byKind.computeIfAbsent(a.getString("kind"), x -> new LinkedHashMap<>(Map.of("raised", 0, "dismissed", 0, "acknowledged", 0)));
            k.merge("raised", 1, (x, y) -> (int) x + (int) y);
            if ("Dismissed".equals(a.getString("status"))) k.merge("dismissed", 1, (x, y) -> (int) x + (int) y);
            if ("Acknowledged".equals(a.getString("status"))) k.merge("acknowledged", 1, (x, y) -> (int) x + (int) y);
        }
        byKind.values().forEach(k -> k.put("dismissed_share", (int) k.get("raised") == 0 ? 0
                : Math.round(100.0 * (int) k.get("dismissed") / (int) k.get("raised")) / 100.0));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alerts_by_kind", byKind);
        // dismissed notes tell the next version what was noise
        out.put("dismissal_notes", docs.find(AnomalyDetector.ALERT, Map.of("status", "Dismissed"), 200).stream()
                .filter(a -> a.getString("raised_by") != null && a.getString("raised_by").startsWith(prefix) && a.getString("note") != null)
                .limit(10).map(a -> a.getString("kind") + " " + a.getString("detail") + ": " + a.getString("note")).toList());
        return out;
    }
}
