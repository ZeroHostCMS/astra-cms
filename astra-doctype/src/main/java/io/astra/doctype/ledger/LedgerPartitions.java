package io.astra.doctype.ledger;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

/** Creates this month's and the next two months' ledger partitions at startup, before postings need them. */
@ApplicationScoped
public class LedgerPartitions {

    private static final Logger LOG = Logger.getLogger(LedgerPartitions.class);

    @Inject AgroalDataSource dataSource;
    @Inject LedgerStore ledger;

    void onStart(@Observes StartupEvent ev) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            for (int i = 0; i < 3; i++) {
                LocalDate month = LocalDate.now().plusMonths(i);
                try {
                    ledger.ensurePartition(conn, month);
                } catch (SQLException e) {
                    // Another pod may be creating the same partition; the next posting retries.
                    LOG.warnf("Ledger partition for %s not created yet: %s", month.withDayOfMonth(1), e.getMessage());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot prepare ledger partitions", e);
        }
    }
}
