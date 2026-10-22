package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** The MCP server: same identity, tenant and permissions as the REST API, with personal data masked. */
@QuarkusTest
class McpTest {

    @Inject DocumentStore store;

    private static ValidatableResponse rpc(String tenant, String method, String params) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}")
                .post("/mcp").then().statusCode(200).body("id", equalTo(7));
    }

    private String submittedWaybill(String tenant) {
        ExpressFixtures.tenant(store, tenant);
        return TenantContext.call(tenant, () -> {
            String order = store.insert("CustomerOrder", Map.of("customer", "C001", "receiver_name", "Nguyễn Văn An",
                    "receiver_phone", "0901234567", "receiver_address", "12 Lê Lợi, Quận 1", "weight_kg", 1), "u-admin").name();
            return store.submit("CustomerOrder", order).orElseThrow().getString("waybill");
        });
    }

    @Test
    void anonymousAgentsAreRejected() {
        given().contentType("application/json").body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .post("/mcp").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/mcp-a/admin")
    void handshakeAndToolCatalogue() {
        rpc("mcp-a", "initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}")
                .body("result.protocolVersion", equalTo("2025-11-25"))
                .body("result.capabilities.tools.listChanged", equalTo(false))
                .body("result.serverInfo.name", equalTo("astra"));
        given().header("X-Astra-Tenant", "mcp-a").contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").post("/mcp").then().statusCode(202);
        rpc("mcp-a", "tools/list", "{}")
                .header("X-Tx-LSN", nullValue())
                .body("result.tools.name", hasItems("list_doctypes", "search_documents", "get_document", "create_document",
                        "ledger_trial_balance", "track_waybill", "cod_outstanding"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/mcp-b/admin")
    void personalDataIsMaskedBeforeItReachesTheModel() {
        String waybill = submittedWaybill("mcp-b");
        rpc("mcp-b", "tools/call", "{\"name\":\"search_documents\",\"arguments\":{\"doctype\":\"CustomerOrder\",\"limit\":5}}")
                .body("result.isError", equalTo(false))
                .body("result.structuredContent.items[0].data.receiver_phone", equalTo("*******567"))
                .body("result.structuredContent.items[0].data.receiver_name", equalTo("N*** V*** A***"))
                .body("result.structuredContent.items[0].data.shipping_fee", equalTo(30000))
                .body("result.content[0].text", not(containsString("0901234567")));
        rpc("mcp-b", "tools/call", "{\"name\":\"track_waybill\",\"arguments\":{\"waybill\":\"" + waybill + "\"}}")
                .body("result.structuredContent.waybill.data.ops_status", equalTo("Created"))
                .body("result.structuredContent.waybill.data.receiver_name", equalTo("N*** V*** A***"))
                .body("result.structuredContent.order.data.receiver_address", equalTo("1*** L*** L*** Q*** 1***"));
        rpc("mcp-b", "tools/call", "{\"name\":\"list_doctypes\",\"arguments\":{}}")
                .body("result.structuredContent.items.find { it.name == 'CustomerOrder' }.fields.find { it.name == 'receiver_phone' }.pii",
                        equalTo(true));
    }

    @Test
    @TestSecurity(user = "u-care", roles = "/tenants/mcp-c/care")
    void agentsActWithTheUsersPermissions() {
        ExpressFixtures.tenant(store, "mcp-c");
        rpc("mcp-c", "tools/call", "{\"name\":\"create_document\",\"arguments\":{\"doctype\":\"CustomerOrder\",\"data\":{}}}")
                .header("X-Tx-LSN", nullValue())                       // refused: nothing was written
                .body("result.isError", equalTo(true))
                .body("result.content[0].text", containsString("not permitted: create CustomerOrder"));
        rpc("mcp-c", "tools/call", "{\"name\":\"ledger_trial_balance\",\"arguments\":{}}")
                .body("result.isError", equalTo(true));
        rpc("mcp-c", "tools/call", "{\"name\":\"list_doctypes\",\"arguments\":{}}")
                .header("X-Tx-LSN", nullValue())                       // a read reports no WAL position
                .body("result.structuredContent.items.name", hasItem("SupportTicket"))
                .body("result.structuredContent.items.name", not(hasItem("Account")));
        rpc("mcp-c", "tools/call", "{\"name\":\"create_document\",\"arguments\":{\"doctype\":\"SupportTicket\",\"data\":{\"subject\":\"Khách báo hàng bị vỡ\"}}}")
                .header("X-Tx-LSN", matchesPattern("[0-9A-F]+/[0-9A-F]+")) // a committed write does
                .body("result.isError", equalTo(false))
                .body("result.structuredContent.data.priority", equalTo("High"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/mcp-d/admin")
    void unknownToolsAndMethodsAreReported() {
        rpc("mcp-d", "tools/call", "{\"name\":\"drop_database\",\"arguments\":{}}")
                .body("result.isError", equalTo(true)).body("result.content[0].text", containsString("unknown tool"));
        rpc("mcp-d", "resources/list", "{}").body("error.code", equalTo(-32601));
    }
}
