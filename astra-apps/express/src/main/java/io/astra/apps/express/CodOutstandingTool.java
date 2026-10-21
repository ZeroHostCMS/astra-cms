package io.astra.apps.express;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.ledger.GeneralLedger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool: COD the company still owes each shop, straight from the ledger (credit balance of 3388). */
@ApplicationScoped
public class CodOutstandingTool implements McpTool {

    @Inject DocumentService docs;
    @Inject GeneralLedger ledger;

    @Override public String name() { return "cod_outstanding"; }

    @Override public String description() {
        return "Cash-on-delivery collected for shops but not yet paid out to them, per customer, from the general ledger.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties",
                Map.of("customer", Map.of("type", "string", "description", "customer code; omit for every customer")));
    }

    @Override public List<String> reads() { return List.of("Customer", "Account"); }

    @Override public Object call(Map<String, Object> arguments) {
        Object only = arguments.get("customer");
        return docs.find("Customer", Map.of(), 200).stream()
                .filter(c -> only == null || c.name().equals(only.toString()))
                .map(c -> {
                    BigDecimal owed = ledger.balance(Accounts.COD_PAYABLE, c.name()).negate();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("customer", c.name());
                    m.put("customer_name", c.getString("customer_name"));
                    m.put("cod_owed_vnd", owed);
                    return m;
                })
                .filter(m -> ((BigDecimal) m.get("cod_owed_vnd")).signum() != 0)
                .toList();
    }
}
