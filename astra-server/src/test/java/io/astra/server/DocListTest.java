package io.astra.server;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.DocumentStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lists as the performance work left them: keyset pages through {@code X-Next-Cursor}, and projected-field
 * filters that the planner can serve from their index while row-level security is on
 * (docs/benchmark-doctype-jsonb-the-right-way.md, the leakproof accessor).
 */
@QuarkusTest
class DocListTest {

    @Inject DocumentStore store;
    @Inject AgroalDataSource dataSource;

    private static RequestSpecification as(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/ls-page/admin")
    void cursorPagesReturnEveryDocumentOnceNewestFirst() {
        String t = "ls-page";
        ExpressFixtures.tenant(store, t);
        for (int i = 1; i <= 7; i++) {
            as(t).body("{\"code\":\"P0" + i + "\",\"customer_name\":\"Page " + i + "\",\"segment\":\"B2B\"}")
                    .post("/api/doc/Customer").then().statusCode(201);
        }
        List<String> all = as(t).queryParam("segment", "B2B").queryParam("limit", 200)
                .get("/api/doc/Customer").then().statusCode(200).extract().path("name");

        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            RequestSpecification req = as(t).queryParam("segment", "B2B").queryParam("limit", 3);
            if (cursor != null) req = req.queryParam("after", cursor);
            ExtractableResponse<Response> page = req.get("/api/doc/Customer").then().statusCode(200).extract();
            List<String> names = page.path("name");
            paged.addAll(names);
            cursor = page.header("X-Next-Cursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertEquals(all, paged, "cursor pages must equal the single newest-first list");
        assertEquals(paged.size(), new HashSet<>(paged).size(), "no document twice");
        assertTrue(all.size() >= 8, "seven new B2B customers plus the fixture customer");
        assertEquals((all.size() + 2) / 3, pages);

        as(t).queryParam("after", "not-a-cursor").get("/api/doc/Customer")
                .then().statusCode(422).body("errors", hasItem("invalid list cursor"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/ls-plan/admin")
    void projectedFilterIsAnIndexConditionUnderRowLevelSecurity() throws Exception {
        String t = "ls-plan";
        ExpressFixtures.tenant(store, t);
        String plan = explain(t, "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' "
                + "AND astra_ext.astra_field(data, 'customer') = 'C001' ORDER BY modified_at DESC, name DESC LIMIT 21");
        assertTrue(plan.contains("astra_doc_pf_customer"), plan);
        assertTrue(plan.contains("Index Cond") && plan.contains("astra_field(data, 'customer'::text) = 'C001'::text"), plan);

        // The same filter written with ->> stays a Filter after the policy: what every list did before.
        String legacy = explain(t, "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' "
                + "AND (data->>'customer') = 'C001' ORDER BY modified_at DESC LIMIT 20");
        assertTrue(legacy.contains("Filter: ((data ->> 'customer'::text) = 'C001'::text)"), legacy);
    }

    /** EXPLAIN as the application role with the tenant bound, sequential scans disabled to expose index use. */
    private String explain(String tenant, String sql) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('astra.tenant', '" + tenant + "', true)");
                st.execute("SET LOCAL enable_seqscan = off");
                StringBuilder plan = new StringBuilder();
                try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                }
                return plan.toString();
            } finally {
                c.rollback();
            }
        }
    }
}
