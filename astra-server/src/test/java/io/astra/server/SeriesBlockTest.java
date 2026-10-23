package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.doc.Document;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CustomerOrder uses a naming series with {@code gapless: false}: numbers come from blocks each instance
 * reserves in its own transaction, so concurrent creations never wait on the counter row. Names must still
 * be unique and keep their width.
 */
@QuarkusTest
class SeriesBlockTest {

    @Inject DocumentStore store;

    @Test
    void concurrentOrdersGetUniqueNumbersWithoutTheCounterLock() throws Exception {
        String t = "series-blocks";
        ExpressFixtures.tenant(store, t);
        String prefix = "ORD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Map<String, Object> order = Map.of("customer", "C001", "receiver_name", "Tran Thi B", "receiver_phone", "0912345678",
                "receiver_address", "1 Trang Tien, Ha Noi", "weight_kg", 1.0);

        int n = 300;
        List<Future<String>> names = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < n; i++) {
                names.add(pool.submit(() -> TenantContext.call(t, () -> {
                    Document d = store.insert("CustomerOrder", new java.util.HashMap<>(order), "series-test");
                    return d.name();
                })));
            }
        }
        List<String> got = new ArrayList<>();
        for (Future<String> f : names) got.add(f.get());

        assertEquals(n, new HashSet<>(got).size(), "every order gets its own number");
        assertTrue(got.stream().allMatch(s -> s.matches(java.util.regex.Pattern.quote(prefix) + "\\d{7}")), got.toString());
    }
}
