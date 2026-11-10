package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.release.Releases;
import io.astra.spi.release.SlotContract;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scores operational events with the tenant's anomaly detector (slot {@code ops.anomaly}, a Wasm module with
 * {@code check(kind, a, b, c, d) -> i64}) and raises an {@link #ALERT} for the role that acts on it. A detector that
 * fails or misses its deadline never blocks the event; it only counts against the release's health.
 */
@ApplicationScoped
public class AnomalyDetector {

    static final String SLOT = "ops.anomaly";
    static final String ALERT = "OpsAlert";
    static final String FUNCTION = "check";
    static final long TIMEOUT_MS = 20;

    /** Event kinds of the ABI, their alert label and the role that handles them. */
    enum Kind {
        WEIGHT(1, "Weight mismatch", "ops"), COD(2, "COD payout", "accountant"), DELIVERY(3, "Failed delivery", "care");

        final int code;
        final String label;
        final String role;

        Kind(int code, String label, String role) { this.code = code; this.label = label; this.role = role; }

        static Kind of(String label) {
            for (Kind k : values()) if (k.label.equals(label)) return k;
            throw new IllegalArgumentException(label);
        }
    }

    private static final System.Logger LOG = System.getLogger(AnomalyDetector.class.getName());

    @Inject DocumentService docs;
    @Inject ScriptRuntime scripts;
    @Inject Releases releases;

    /** Scores one event with the live detector; raises an alert when it answers non-zero. */
    void check(Kind kind, long a, long b, long c, long d, String refDoctype, String refName, String detail) {
        Releases.Artifact detector = releases.artifact(SLOT, null).orElse(null);
        if (detector == null) return;
        long started = System.nanoTime();
        long score;
        try {
            score = scripts.callLong(detector.bytes(), FUNCTION, TIMEOUT_MS, kind.code, a, b, c, d);
            releases.record(detector, true, System.nanoTime() - started);
        } catch (ScriptException e) {
            releases.record(detector, false, System.nanoTime() - started);
            LOG.log(System.Logger.Level.WARNING, "detector {0} failed on {1} {2} ({3}); event not scored", detector.label(), refDoctype, refName, e.reason());
            return;
        }
        if (score <= 0) return;
        Document alert = docs.insert(ALERT, Map.of("kind", kind.label, "status", "Open"), "detector");
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("severity", score / 100);
        system.put("reason", score % 100);
        system.put("detail", detail);
        system.put("ref_doctype", refDoctype);
        system.put("ref_name", refName);
        system.put("assigned_role", kind.role);
        system.put("raised_by", detector.label());
        system.put("release", detector.release());
        docs.updateSystemFields(ALERT, alert.name(), alert.version(), system);
    }

    /** {@code ops.anomaly}: a Wasm module exporting {@code check}; the self-test scores one normal and one odd event of each kind. */
    @ApplicationScoped
    static class Slot implements SlotContract {
        @Inject ScriptRuntime scripts;

        @Override public String slot() { return SLOT; }
        @Override public String tier() { return "wasm"; }

        @Override public String selfTest(byte[] module) {
            long[][] events = {{1, 1_000, 1_020, 0, 0}, {1, 1_000, 1_800, 0, 0}, {2, 1_000_000, 1_000_000, 2, 0}, {2, 1_000_000, 600_000, 2, 0},
                    {3, 1, 1, 20, 0}, {3, 1, 9, 20, 0}};
            StringBuilder scores = new StringBuilder();
            try {
                for (long[] e : events) {
                    long s = scripts.callLong(module, FUNCTION, TIMEOUT_MS, e);
                    if (s < 0 || s >= 10_000) throw new DocValidationException("check answered " + s + "; expected 0 or severity × 100 + reason");
                    scores.append(scores.isEmpty() ? "" : " ").append(s);
                }
            } catch (ScriptException e) {
                throw new DocValidationException("module rejected (" + e.reason() + "): " + e.getMessage());
            }
            return "check on 3 normal + 3 odd sample events → " + scores;
        }
    }
}
