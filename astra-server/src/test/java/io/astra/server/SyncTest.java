package io.astra.server;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.DocumentStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Sync-Aware LSN in the kernel. The Rust worker is not running in tests, so the watermark it would
 * write is written here directly; the worker itself is exercised by the demo stack.
 */
@QuarkusTest
class SyncTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"x\",\"weight_kg\":1}";

    @Inject DocumentStore store;
    @Inject AgroalDataSource dataSource;

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/sync-a/admin")
    void writesReportTheirLsnAndReplicaReadsWaitForTheWatermark() throws Exception {
        ExpressFixtures.tenant(store, "sync-a");
        String lsn = given().header("X-Astra-Tenant", "sync-a").contentType("application/json").body(ORDER)
                .post("/api/doc/CustomerOrder").then().statusCode(201)
                .header("X-Tx-LSN", matchesPattern("[0-9A-F]+/[0-9A-F]+")).extract().header("X-Tx-LSN");

        given().header("X-Astra-Tenant", "sync-a").header("X-Required-LSN", lsn)
                .get("/api/replica/replica_order").then().statusCode(202)
                .body("status", equalTo("sync-pending")).body("requiredLsn", equalTo(lsn));

        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO _cdc_sync_watermark (slot, last_lsn) VALUES ('test', pg_current_wal_lsn()) "
                        + "ON CONFLICT (slot) DO UPDATE SET last_lsn = excluded.last_lsn, updated_at = now()")) {
            ps.executeUpdate();
        }
        given().header("X-Astra-Tenant", "sync-a").header("X-Required-LSN", lsn)
                .get("/api/replica/replica_order").then().statusCode(200)
                .body("source", equalTo("CustomerOrder")).body("sync.slot", equalTo("test"));
    }

    @Test
    @TestSecurity(user = "u-care", roles = "/tenants/sync-b/care")
    void replicaReadsAreCheckedAgainstTheSourceDocType() {
        given().header("X-Astra-Tenant", "sync-b").get("/api/replica/replica_waybill").then().statusCode(200);
        given().header("X-Astra-Tenant", "sync-b").get("/api/replica/astra_doc").then().statusCode(404);
        given().header("X-Astra-Tenant", "sync-b").header("X-Required-LSN", "not-an-lsn")
                .get("/api/replica/replica_waybill").then().statusCode(400);
        given().header("X-Astra-Tenant", "sync-b").get("/api/sync/status").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "u-accountant", roles = "/tenants/sync-c/accountant")
    void rolesWithoutReadOnTheSourceCannotReadItsReplica() {
        given().header("X-Astra-Tenant", "sync-c").get("/api/replica/replica_waybill").then().statusCode(403);
    }
}
