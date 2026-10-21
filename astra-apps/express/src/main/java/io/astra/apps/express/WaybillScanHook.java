package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.ledger.GeneralLedger;
import io.astra.spi.ledger.GeneralLedger.Entry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The parcel state machine. Every change of status or hub is checked against the allowed transitions
 * and appended to {@code scan_log}, which callers cannot write, so the log is the parcel's history.
 * Delivery records the COD the courier collected: Dr 1388 (held by couriers) / Cr 3388 (owed to the shop).
 */
@ApplicationScoped
public class WaybillScanHook implements DocLifecycleHook {

    static final Map<String, Set<String>> NEXT = Map.of(
            "Created", Set.of("Picked Up", "Cancelled"),
            "Picked Up", Set.of("At Hub", "Cancelled"),
            "At Hub", Set.of("At Hub", "Out for Delivery", "Returned"),
            "Out for Delivery", Set.of("Delivered", "Failed Delivery"),
            "Failed Delivery", Set.of("Out for Delivery", "Returned"),
            "Delivered", Set.of(),
            "Returned", Set.of(),
            "Cancelled", Set.of());

    @Inject DocumentService docs;
    @Inject GeneralLedger ledger;

    @Override
    public String doctype() { return "Waybill"; }

    @Override
    @SuppressWarnings("unchecked")
    public void beforeSave(Document doc) {
        List<Map<String, Object>> log = doc.get("scan_log") instanceof List<?> l
                ? new ArrayList<>((List<Map<String, Object>>) l) : new ArrayList<>();
        Map<String, Object> last = log.isEmpty() ? null : log.getLast();
        String status = doc.getString("ops_status");
        String hub = doc.getString("current_hub");

        if (last != null) {
            String from = (String) last.get("status");
            boolean same = from.equals(status) && Objects.equals(last.get("hub"), hub);
            if (same) return;
            if (!NEXT.getOrDefault(from, Set.of()).contains(status)) {
                throw new DocValidationException("ops_status cannot go from '" + from + "' to '" + status + "'");
            }
        } else if (!"Created".equals(status)) {
            throw new DocValidationException("a new waybill starts as 'Created'");
        }
        if ("At Hub".equals(status) && (hub == null || hub.isBlank())) {
            throw new DocValidationException("current_hub is required when ops_status is 'At Hub'");
        }

        if ("Delivered".equals(status)) postCollectedCod(doc);

        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("status", status);
        scan.put("hub", hub);
        scan.put("at", Instant.now().toString());
        log.add(scan);
        doc.set("scan_log", log);
    }

    private void postCollectedCod(Document waybill) {
        Document order = docs.get("CustomerOrder", waybill.getString("order")).orElseThrow();
        BigDecimal cod = BigDecimal.valueOf(Numbers.toDouble(order.get("cod_amount")));
        String customer = order.getString("customer");
        ledger.post("Waybill", waybill.name(), LocalDate.now(), List.of(
                Entry.debit(Accounts.COD_HELD, null, cod),
                Entry.credit(Accounts.COD_PAYABLE, customer, cod)), "COD collected on " + waybill.name());
    }
}
