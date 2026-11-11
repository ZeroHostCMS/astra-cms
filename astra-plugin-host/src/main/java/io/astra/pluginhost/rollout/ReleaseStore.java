package io.astra.pluginhost.rollout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.DocumentStore;
import io.astra.doctype.store.TenantTransactions;
import io.astra.doctype.store.Tenants;
import io.astra.spi.doc.Document;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads releases and pins of the current tenant, and writes the fields the rollout owns on approved (submitted, so
 * otherwise immutable) releases: stage, pods and health counters. Those writes go straight to the row's JSONB under
 * the tenant's row-level security; nothing else of the release can change after approval.
 */
@ApplicationScoped
public class ReleaseStore {

    static final String RELEASE = "AppRelease";
    static final String PIN = "AppPin";

    @Inject DocumentStore docs;
    @Inject TenantTransactions tx;
    @Inject AgroalDataSource dataSource;
    @Inject ObjectMapper json;

    /** Approved releases that serve calls (canary or live), plus the ones the tenant's pins name. */
    List<Document> serving() {
        List<Document> out = new ArrayList<>(docs.find(RELEASE, Map.of("stage", "Canary", "docstatus", "1"), 200));
        out.addAll(docs.find(RELEASE, Map.of("stage", "Live", "docstatus", "1"), 200));
        for (Document pin : pins()) {
            String app = pin.getString("app_id");
            String version = pin.getString("version");
            if (out.stream().anyMatch(r -> app.equals(r.getString("app_id")) && version.equals(r.getString("version")))) continue;
            docs.find(RELEASE, Map.of("app_id", app, "version", version, "docstatus", "1"), 5).stream()
                    .filter(r -> !"Rolled back".equals(r.getString("stage")))
                    .findFirst().ifPresent(out::add);
        }
        return out;
    }

    List<Document> pins() {
        return docs.find(PIN, Map.of(), 200);
    }

    List<Document> canaries() {
        return docs.find(RELEASE, Map.of("stage", "Canary", "docstatus", "1"), 200);
    }

    List<Document> live(String slot) {
        return docs.find(RELEASE, Map.of("slot", slot, "stage", "Live", "docstatus", "1"), 50);
    }

    /** Merges {@code fields} into an approved release's data. */
    void patch(String release, Map<String, Object> fields) {
        patchIf(release, null, fields);
    }

    /**
     * Merges {@code fields} only while the release is still approved and, when {@code expectedStage} is given, still at
     * that stage; returns whether a row changed. Lets the promoter move a canary to Live and act on the result
     * atomically (if an admin cancelled the canary first, the patch hits no row and the caller does not retire the old
     * Live release, so the slot is never left with nothing serving).
     */
    boolean patchIf(String release, String expectedStage, Map<String, Object> fields) {
        String patch = toJson(fields);
        return tx.inTenantTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE astra_doc SET data = data || ?::jsonb, modified_at = now(), "
                    + "version = version + 1 WHERE doctype = ? AND name = ? AND docstatus = 1"
                    + (expectedStage == null ? "" : " AND data->>'stage' = ?"))) {
                ps.setString(1, patch);
                ps.setString(2, RELEASE);
                ps.setString(3, release);
                if (expectedStage != null) ps.setString(4, expectedStage);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Adds this pod's calls and errors since the last flush; p99 becomes this pod's p99 over the same window. */
    void addHealth(String release, long calls, long errors, double p99Ms) {
        tx.inTenantTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE astra_doc SET data = data || jsonb_build_object(
                        'calls',  COALESCE((data->>'calls')::bigint, 0) + ?,
                        'errors', COALESCE((data->>'errors')::bigint, 0) + ?,
                        'p99_ms', round(?::numeric, 2))
                    WHERE doctype = ? AND name = ? AND docstatus = 1""")) {
                ps.setLong(1, calls);
                ps.setLong(2, errors);
                ps.setDouble(3, p99Ms);
                ps.setString(4, RELEASE);
                ps.setString(5, release);
                ps.executeUpdate();
            }
            return null;
        });
    }

    Optional<Document> get(String release) {
        return docs.get(RELEASE, release);
    }

    /** This pod is alive: upsert its heartbeat row. */
    void heartbeat(String pod) {
        withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO astra_pod (name, seen_at) VALUES (?, now()) ON CONFLICT (name) DO UPDATE SET seen_at = now()")) {
                ps.setString(1, pod);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Pods seen in the last 10 seconds, by name. */
    List<String> livePods() {
        return withConnection(conn -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name FROM astra_pod WHERE seen_at > now() - interval '10 seconds' ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    /**
     * Runs {@code work} if this pod wins the cluster-wide promoter lock (a PostgreSQL advisory lock held for the
     * duration of the work), so exactly one pod decides promotions and rollbacks at a time.
     */
    boolean ifPromoter(Runnable work) {
        return withConnection(conn -> {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended('astra-rollout', 0))");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) return false;
                }
                work.run();
                return true;
            } finally {
                conn.rollback(); // releases the lock; the lock transaction itself wrote nothing
            }
        });
    }

    List<String> tenants() {
        return Tenants.list(dataSource);
    }

    private <T> T withConnection(TenantTransactions.SqlWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            return work.run(conn);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Map<String, Object> fields) {
        try {
            return json.writeValueAsString(new LinkedHashMap<>(fields));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
