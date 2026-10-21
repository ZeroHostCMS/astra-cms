package io.astra.doctype.api;

import io.astra.doctype.ledger.LedgerStore;
import io.astra.doctype.permission.DocPermissions;
import io.astra.doctype.permission.DocPermissions.Action;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.tenant.TenantContext;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

/** Read access to the tenant's general ledger. Whoever may read the chart of accounts may read the ledger. */
@Path("/api/ledger")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class LedgerResource {

    @Inject LedgerStore ledger;
    @Inject DocPermissions permissions;
    @Inject TenantRequest tenant;

    @ConfigProperty(name = "astra.ledger.account-doctype", defaultValue = "Account")
    String accountDoctype;

    @GET
    @Path("/trial-balance")
    public List<Map<String, Object>> trialBalance() {
        return scoped(() -> ledger.trialBalance());
    }

    @GET
    @Path("/entries")
    public List<Map<String, Object>> entries(@QueryParam("voucher_no") String voucherNo, @QueryParam("account") String account,
                                             @QueryParam("limit") @DefaultValue("50") int limit) {
        return scoped(() -> ledger.entries(voucherNo, account, Math.min(limit, 500)));
    }

    @GET
    @Path("/partitions")
    public List<Map<String, Object>> partitions() {
        return scoped(() -> ledger.partitions());
    }

    private <T> T scoped(ScopedValue.CallableOp<T, RuntimeException> work) {
        permissions.require(accountDoctype, tenant.roles(), Action.READ);
        return TenantContext.call(tenant.tenantId(), work);
    }
}
