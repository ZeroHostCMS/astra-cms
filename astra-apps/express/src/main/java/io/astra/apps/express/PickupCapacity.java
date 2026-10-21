package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.kv.Counters;
import io.astra.spi.tx.TccParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;

/**
 * TCC participant: a day's pickup slots in Redis. Try reserves a slot (or refuses when the day is full),
 * Confirm turns the reservation into a booked pickup, Cancel gives the slot back. Each branch acts once in
 * phase 2, a cancel for a try that never ran gives nothing back, and a try arriving after its cancel is refused.
 */
@ApplicationScoped
public class PickupCapacity implements TccParticipant {

    static final String NAME = "pickup-capacity";
    private static final Duration KEEP = Duration.ofDays(3);

    @Inject Counters counters;

    @Override public String name() { return NAME; }

    @Override
    public void prepare(String branchId, String day, Map<String, Object> args) {
        if (counters.get("tcc-done:" + branchId) > 0) {
            throw new DocValidationException("pickup reservation " + branchId + " was already cancelled"); // late try after rollback
        }
        counters.add("tcc-try:" + branchId, 1, KEEP);
        long capacity = Math.round(Numbers.toDouble(args.get("capacity")));
        long reserved = counters.add(reservedKey(day), 1, KEEP);
        if (reserved + counters.get(bookedKey(day)) > capacity) {
            // The branch is registered, so Seata calls rollback, which gives this slot back.
            throw new DocValidationException("no pickup slot left for " + day + " (capacity " + capacity + ")");
        }
    }

    @Override
    public void commit(String branchId, String day, Map<String, Object> args) {
        if (!counters.markOnce("tcc-done:" + branchId, KEEP)) return;
        counters.add(reservedKey(day), -1, KEEP);
        counters.add(bookedKey(day), 1, KEEP);
    }

    @Override
    public void rollback(String branchId, String day, Map<String, Object> args) {
        if (!counters.markOnce("tcc-done:" + branchId, KEEP)) return;
        if (counters.get("tcc-try:" + branchId) > 0) counters.add(reservedKey(day), -1, KEEP); // nothing to give back after an empty rollback
    }

    static String reservedKey(String day) { return "pickup:" + day + ":reserved"; }

    static String bookedKey(String day) { return "pickup:" + day + ":booked"; }
}
