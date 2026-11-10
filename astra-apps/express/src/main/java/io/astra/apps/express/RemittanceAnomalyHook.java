package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** COD payouts for the anomaly detector (kind 2): owed versus paid, and how long after collection, when a payout is saved. */
@ApplicationScoped
public class RemittanceAnomalyHook implements DocLifecycleHook {

    @Inject AnomalyDetector detector;

    @Override
    public String doctype() { return "CODRemittance"; }

    @Override
    public void afterSave(Document payout) {
        if (payout.version() != 1) return;   // the first save only; edits of a draft are not new events
        long owed = Math.round(Numbers.toDouble(payout.get("outstanding")));
        long paid = Math.round(Numbers.toDouble(payout.get("amount")));
        String until = payout.getString("collected_until");
        long days = until == null || until.isBlank() ? 0 : ChronoUnit.DAYS.between(LocalDate.parse(until.substring(0, 10)), LocalDate.now());
        detector.check(AnomalyDetector.Kind.COD, owed, paid, days, 0, "CODRemittance", payout.name(),
                String.format("%s: nợ shop %,d ₫, chi %,d ₫, sau %d ngày", payout.getString("customer"), owed, paid, days));
    }
}
