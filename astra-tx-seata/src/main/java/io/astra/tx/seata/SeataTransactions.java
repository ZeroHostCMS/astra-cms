package io.astra.tx.seata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.GlobalTransactionCoordinator;
import io.astra.spi.tenant.TenantContext;
import io.astra.spi.tx.GlobalTransactions;
import io.astra.spi.tx.TccParticipant;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.exception.TransactionException;
import org.apache.seata.integration.tx.api.util.ProxyUtil;
import org.apache.seata.rm.RMClient;
import org.apache.seata.rm.datasource.DataSourceProxy;
import org.apache.seata.tm.TMClient;
import org.apache.seata.tm.api.GlobalTransaction;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Global transactions on Apache Seata. At startup the kernel registers as TM and RM with the Seata
 * server, wraps the BMS datasource for AT mode, and registers one TCC resource that dispatches to app
 * participants. Disabled unless {@code astra.seata.enabled}; then documents fall back to local transactions.
 */
@ApplicationScoped
public class SeataTransactions implements GlobalTransactions, GlobalTransactionCoordinator {

    private static final Logger LOG = Logger.getLogger(SeataTransactions.class);

    @ConfigProperty(name = "astra.seata.enabled", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "astra.seata.server", defaultValue = "127.0.0.1:8091") String server;
    @ConfigProperty(name = "astra.seata.application-id", defaultValue = "astra-server") String applicationId;
    @ConfigProperty(name = "astra.seata.tx-group", defaultValue = "astra_tx_group") String txGroup;
    @ConfigProperty(name = "astra.seata.timeout-ms", defaultValue = "30000") int timeoutMillis;

    @Inject @DataSource("bms") Instance<AgroalDataSource> bms;
    @Inject Instance<TccParticipant> participants;
    @Inject ObjectMapper json;

    private volatile boolean ready;
    private DataSourceProxy bmsProxy;
    private AstraTccAction tcc;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.info("Seata disabled (astra.seata.enabled=false): global DocTypes use local transactions");
            return;
        }
        System.setProperty("service.vgroupMapping." + txGroup, "default");
        System.setProperty("service.default.grouplist", server);
        TMClient.init(applicationId, txGroup);
        RMClient.init(applicationId, txGroup);
        bmsProxy = new DataSourceProxy(bms.get());
        tcc = ProxyUtil.createProxy(new AstraTccDispatcher(participants.stream().toList(), json));
        ready = true;
        LOG.infof("Seata connected: TM/RM %s in group %s via %s; AT on datasource bms; TCC participants %s",
                applicationId, txGroup, server, participants.stream().map(TccParticipant::name).toList());
    }

    @Override
    public boolean available() {
        return ready;
    }

    @Override
    public Optional<String> currentXid() {
        return Optional.ofNullable(RootContext.getXID());
    }

    @Override
    public <T> T inGlobal(String name, Supplier<T> work) {
        if (RootContext.inGlobalTransaction()) return work.get();
        GlobalTransaction gtx = GlobalTransactionContext.getCurrentOrCreate();
        try {
            gtx.begin(timeoutMillis, name);
        } catch (TransactionException e) {
            throw new IllegalStateException("cannot begin global transaction: " + e.getMessage(), e);
        }
        String xid = gtx.getXid();
        try {
            T result = work.get();
            gtx.commit();
            LOG.infof("Global transaction %s committed (%s)", xid, name);
            return result;
        } catch (TransactionException e) {
            throw new IllegalStateException("global commit failed: " + e.getMessage(), e);
        } catch (Throwable e) {
            // Any failure, Errors included: a branch may already hold global locks that only a rollback releases.
            try {
                gtx.rollback();
                LOG.infof("Global transaction %s rolled back (%s): %s", xid, name, e.getMessage());
            } catch (TransactionException te) {
                LOG.errorf(te, "Global transaction %s rollback failed; the coordinator retries it", xid);
            }
            throw e;
        } finally {
            if (xid != null && xid.equals(RootContext.getXID())) RootContext.unbind();
        }
    }

    @Override
    public javax.sql.DataSource atDataSource(String name) {
        if (!"bms".equals(name) || bmsProxy == null) throw new IllegalArgumentException("no AT datasource " + name);
        return bmsProxy;
    }

    @Override
    public void tcc(String participant, String key, Map<String, Object> arguments) {
        if (!RootContext.inGlobalTransaction()) throw new IllegalStateException("TCC branch outside a global transaction");
        try {
            tcc.prepare(null, TenantContext.require(), participant, key, json.writeValueAsString(arguments));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("TCC arguments must be JSON-serialisable", e);
        }
    }
}
