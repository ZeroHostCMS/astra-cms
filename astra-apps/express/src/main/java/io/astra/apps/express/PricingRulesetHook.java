package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.pricing.PricingEngine;
import io.astra.spi.release.SlotContract;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Business analysts edit price rules on the Desk. Saving refuses a rule set that does not pass the allowlist or does
 * not compile, with the offending line; submitting turns it into a draft release of slot {@code pricing.base}, which
 * goes live on the pods only when an admin approves it.
 */
@ApplicationScoped
public class PricingRulesetHook implements DocLifecycleHook {

    @Inject DocumentService docs;
    @Inject Instance<SlotContract> slots;

    @Override
    public String doctype() { return "PricingRuleset"; }

    @Override
    public void beforeSave(Document ruleset) {
        byte[] drl = ruleset.getString("rules").getBytes(StandardCharsets.UTF_8);
        String summary = rulesSlot().selfTest(drl);
        ruleset.set("rule_count", Integer.parseInt(summary.substring(0, summary.indexOf(' '))));
        ruleset.set("self_test", summary);
    }

    @Override
    public void onSubmit(Document ruleset) {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("slot", PricingEngine.SLOT);
        release.put("tier", "rules");
        release.put("app_id", ruleset.getString("ruleset_name"));
        release.put("version", ruleset.getString("version"));
        release.put("artifact", Base64.getEncoder().encodeToString(ruleset.getString("rules").getBytes(StandardCharsets.UTF_8)));
        release.put("notes", "from " + ruleset.name() + (ruleset.getString("notes") == null ? "" : ": " + ruleset.getString("notes")));
        ruleset.set("release", docs.insert("AppRelease", release, ruleset.owner()).name());
    }

    private SlotContract rulesSlot() {
        return slots.stream().filter(s -> s.slot().equals(PricingEngine.SLOT)).findFirst().orElseThrow();
    }
}
