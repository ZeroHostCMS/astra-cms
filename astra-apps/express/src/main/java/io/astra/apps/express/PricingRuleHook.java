package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Vets an uploaded pricing module before it can price a single order: it must load in the sandbox,
 * export {@code fee}, return a sane amount for a 1 kg domestic parcel, and do so within its deadline.
 */
@ApplicationScoped
public class PricingRuleHook implements DocLifecycleHook {

    @Inject ScriptRuntime scripts;

    @Override
    public String doctype() { return "PricingRule"; }

    @Override
    public void beforeSave(Document rule) {
        byte[] module = Base64.getDecoder().decode(rule.getString("module_wasm"));
        rule.set("module_sha256", sha256(module));
        rule.set("module_bytes", module.length);
        long started = System.nanoTime();
        long fee;
        try {
            fee = PricingRules.fee(scripts, rule, 1.0, "Domestic", false, 0);
        } catch (ScriptException e) {
            throw new DocValidationException("module rejected (" + e.reason() + "): " + e.getMessage());
        }
        if (fee < 0 || fee > 10_000_000) throw new DocValidationException("module rejected: fee(1 kg, Domestic) returned " + fee);
        long micros = (System.nanoTime() - started) / 1_000;
        rule.set("self_test", "fee(1 kg, Domestic) = " + fee + " VND in " + micros + " µs");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
