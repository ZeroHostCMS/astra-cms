package io.astra.server;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.DocumentStore;
import io.astra.doctype.store.MaterialisedProjections;
import io.astra.doctype.store.MonthPartitions;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage layouts: a materialised Currency column (CustomerOrder.cod_amount, projected: materialised) that range
 * filters use through an index under row-level security; and month partitioning (TestParcel, partition: month),
 * with lookups by a dated series name skipping older months, names checked across months, and documents stored
 * before partitioning moved into their month.
 */
@QuarkusTest
class StorageLayoutTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"Le Thi C\",\"receiver_phone\":\"0912345678\","
            + "\"receiver_address\":\"5 Hang Bai, Ha Noi\",\"zone\":\"Domestic\",\"weight_kg\":1.0,\"cod_amount\":%s}";

    @Inject DocumentStore store;
    @Inject AgroalDataSource dataSource;
    @Inject MaterialisedProjections materialised;
    @Inject MonthPartitions partitions;

    private static RequestSpecification as(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-range/admin")
    void codRangeFiltersUseTheMaterialisedColumn() throws Exception {
        String t = "st-range";
        ExpressFixtures.tenant(store, t);
        for (String cod : List.of("0", "500000", "1200000.50")) {
            as(t).body(ORDER.formatted(cod)).post("/api/doc/CustomerOrder").then().statusCode(201);
        }
        as(t).queryParam("cod_amount__gt", "600000").get("/api/doc/CustomerOrder").then().statusCode(200)
                .body("size()", equalTo(1)).body("[0].data.cod_amount", equalTo(1200000.50f));
        as(t).queryParam("cod_amount__gte", "500000").get("/api/doc/CustomerOrder").then().statusCode(200).body("size()", equalTo(2));
        as(t).queryParam("cod_amount", "0").get("/api/doc/CustomerOrder").then().statusCode(200).body("size()", equalTo(1));
        as(t).queryParam("weight_kg__gt", "1").get("/api/doc/CustomerOrder").then().statusCode(422)
                .body("errors[0]", containsString("projected: materialised"));
        as(t).queryParam("cod_amount__gt", "lots").get("/api/doc/CustomerOrder").then().statusCode(422);

        try (Connection c = tenantConnection(t); Statement st = c.createStatement()) {
            // Currency is stored in hundredths, computed by the trigger.
            try (ResultSet rs = st.executeQuery("SELECT m_cod_amount FROM astra_doc WHERE doctype = 'CustomerOrder' ORDER BY 1")) {
                rs.next(); assertEquals(0L, rs.getLong(1));
                rs.next(); assertEquals(50_000_000L, rs.getLong(1));
                rs.next(); assertEquals(120_000_050L, rs.getLong(1));
            }
            st.execute("SET LOCAL enable_seqscan = off");
            String plan = plan(st, "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND m_cod_amount > 60000000");
            assertTrue(plan.contains("astra_doc_pm_cod_amount") && plan.contains("Index Cond"), plan);
            c.rollback();
        }
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-backfill/admin")
    void backfillRestoresTheColumnForRowsWrittenBeforeIt() throws Exception {
        String t = "st-backfill";
        ExpressFixtures.tenant(store, t);
        as(t).body(ORDER.formatted("750000")).post("/api/doc/CustomerOrder").then().statusCode(201);
        try (Connection c = tenantConnection(t); Statement st = c.createStatement()) {
            // Setting only the column does not fire the trigger (it runs on changes of data): a row as it was
            // before the field was materialised.
            st.executeUpdate("UPDATE astra_doc SET m_cod_amount = NULL WHERE doctype = 'CustomerOrder'");
            c.commit();
        }
        materialised.refill("astra_doc", "cod_amount");
        try (Connection c = tenantConnection(t); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT m_cod_amount FROM astra_doc WHERE doctype = 'CustomerOrder'")) {
            rs.next();
            assertEquals(75_000_000L, rs.getLong(1));
            c.rollback();
        }
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-month/admin")
    void partitionedDocumentsLiveInTheirMonthAndAreFoundByName() throws Exception {
        String t = "st-month";
        ExpressFixtures.tenant(store, t);
        String today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).format(DateTimeFormatter.ofPattern("yyyy_MM"));
        String name = as(t).body("{\"customer\":\"C001\",\"amount\":125000}").post("/api/doc/TestParcel")
                .then().statusCode(201).body("name", org.hamcrest.Matchers.startsWith("PCL-")).extract().path("name");

        try (Connection c = tenantConnection(t); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT tableoid::regclass::text, m_amount FROM astra_doc_txn WHERE name = '" + name + "'")) {
                rs.next();
                assertEquals("astra_doc_txn_" + today, rs.getString(1));
                assertEquals(12_500_000L, rs.getLong(2));
            }
            // An old parcel in an old month, and one named on an old day but created now (an amendment, say):
            // lookups that skip months older than the name's date must still find both.
            st.executeUpdate("INSERT INTO astra_doc_txn (tenant_id, doctype, name, owner, data, created_at, modified_at) VALUES "
                    + "('" + t + "', 'TestParcel', 'PCL-20250115-0000099', 'old', '{\"customer\":\"C001\",\"amount\":1}', '2025-01-15 09:00+07', '2025-01-15 09:00+07'), "
                    + "('" + t + "', 'TestParcel', 'PCL-20250115-0000099-1', 'old', '{\"customer\":\"C001\",\"amount\":2}', now(), now())");
            c.commit();
        }
        partitions.ensurePartitions();
        as(t).get("/api/doc/TestParcel/PCL-20250115-0000099").then().statusCode(200).body("data.amount", equalTo(1));
        as(t).get("/api/doc/TestParcel/PCL-20250115-0000099-1").then().statusCode(200).body("data.amount", equalTo(2));
        // Updates find the old document too, and a Link to it resolves.
        as(t).body("{\"amount\":3}").put("/api/doc/TestParcel/PCL-20250115-0000099").then().statusCode(200).body("data.amount", equalTo(3));
        as(t).body("{\"customer\":\"C001\",\"parcel\":\"PCL-20250115-0000099\"}").post("/api/doc/TestParcel").then().statusCode(201);
        as(t).body("{\"customer\":\"C001\",\"parcel\":\"PCL-20250115-0000098\"}").post("/api/doc/TestParcel").then().statusCode(422);
        as(t).queryParam("amount__gte", "100000").get("/api/doc/TestParcel").then().statusCode(200)
                .body("name", hasItem(name)).body("size()", equalTo(1));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-unique/admin")
    void chosenNamesAreUniqueAcrossMonths() throws Exception {
        String t = "st-unique";
        try (Connection c = tenantConnection(t); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO astra_doc_txn (tenant_id, doctype, name, owner, data, created_at) VALUES "
                    + "('" + t + "', 'TestParcelCode', 'X-1', 'old', '{\"code\":\"X-1\"}', '2025-01-10 09:00+07')");
            c.commit();
        }
        // PostgreSQL's unique key holds created_at, so only the kernel's check stops a duplicate in another month.
        as(t).body("{\"code\":\"X-1\"}").post("/api/doc/TestParcelCode").then().statusCode(422)
                .body("errors", hasItem("TestParcelCode 'X-1' already exists"));
        as(t).body("{\"code\":\"X-2\"}").post("/api/doc/TestParcelCode").then().statusCode(201);
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-move/admin")
    void documentsStoredBeforePartitioningAreMovedToTheirMonth() throws Exception {
        String t = "st-move";
        ExpressFixtures.tenant(store, t);
        try (Connection c = tenantConnection(t);
             PreparedStatement ps = c.prepareStatement("INSERT INTO astra_doc (tenant_id, doctype, name, owner, data, created_at, modified_at) "
                     + "VALUES (?, 'TestParcel', 'PCL-20250215-LEGACY', 'old-kernel', '{\"customer\":\"C001\",\"amount\":10}'::jsonb, "
                     + "'2025-02-15 10:00+07', '2025-02-15 10:00+07')")) {
            ps.setString(1, t);
            ps.executeUpdate();
            c.commit();
        }
        partitions.moveExistingDocuments();

        as(t).get("/api/doc/TestParcel/PCL-20250215-LEGACY").then().statusCode(200);
        try (Connection c = tenantConnection(t); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT tableoid::regclass::text, m_amount FROM astra_doc_txn WHERE name = 'PCL-20250215-LEGACY'")) {
                rs.next();
                assertEquals("astra_doc_txn_2025_02", rs.getString(1), "moved into its own month, not the default partition");
                assertEquals(1_000L, rs.getLong(2), "the trigger fills the materialised column on the move");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM astra_doc WHERE name = 'PCL-20250215-LEGACY'")) {
                rs.next(); assertEquals(0, rs.getInt(1));
            }
            c.rollback();
        }
        as(t).queryParam("created_at__lt", "2025-03-01").get("/api/doc/TestParcel").then().statusCode(200)
                .body("name", hasItem("PCL-20250215-LEGACY"));
    }

    private static String plan(Statement st, String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) plan.append(rs.getString(1)).append('\n');
        }
        return plan.toString();
    }

    /** A transaction on the application role with the tenant bound, as the kernel opens it; the caller ends it. */
    private Connection tenantConnection(String tenant) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('astra.tenant', ?, true)")) {
            ps.setString(1, tenant);
            ps.execute();
        }
        return c;
    }
}
