package io.astra.apps.hrm;

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
import java.util.List;

/**
 * Keeps a component computable: percentages need a rate, Percent of Gross is for deductions, and a Wasm
 * formula must load in the sandbox, export {@code kpi} and price a full month of 1,000 pieces sanely within
 * its deadline before any payroll can use it.
 */
@ApplicationScoped
public class SalaryComponentHook implements DocLifecycleHook {

    private static final List<String> PERCENT = List.of("Percent of Base", "Percent of Gross");

    @Inject ScriptRuntime scripts;

    @Override
    public String doctype() { return "SalaryComponent"; }

    @Override
    public void beforeSave(Document c) {
        String abbr = c.getString("abbr");
        if (abbr != null && !abbr.matches("[A-Z][A-Z0-9_]{1,19}")) {
            throw new DocValidationException("abbr must be upper-case letters, digits or '_', e.g. BHXH");
        }
        String calculation = c.getString("calculation");
        double rate = Values.number(c.get("rate"));
        if (PERCENT.contains(calculation) && !(rate > 0 && rate <= 100)) {
            throw new DocValidationException(calculation + " needs a rate between 0 and 100 (%)");
        }
        if ("Percent of Gross".equals(calculation) && !"Deduction".equals(c.getString("component_type"))) {
            throw new DocValidationException("Percent of Gross is only for deductions");
        }
        if (Values.number(c.get("amount")) < 0 || Values.number(c.get("base_cap")) < 0) {
            throw new DocValidationException("amount and base_cap must not be negative");
        }
        String wasm = c.getString("module_wasm");
        if (!"Wasm".equals(calculation)) {
            if (wasm != null && !wasm.isBlank()) throw new DocValidationException("module_wasm is only used by Wasm components");
            c.set("module_sha256", null).set("self_test", null);
            return;
        }
        if (wasm == null || wasm.isBlank()) throw new DocValidationException("a Wasm component needs module_wasm");
        byte[] module = Base64.getDecoder().decode(wasm);
        c.set("module_sha256", sha256(module));
        long started = System.nanoTime();
        long bonus;
        try {
            bonus = SalaryCalculator.kpi(scripts, c, 1_000, 260, 26);
        } catch (ScriptException e) {
            throw new DocValidationException("module rejected (" + e.reason() + "): " + e.getMessage());
        }
        if (bonus < 0 || bonus > 1_000_000_000L) throw new DocValidationException("module rejected: kpi(1000 pieces, 26/26 days) returned " + bonus);
        long micros = (System.nanoTime() - started) / 1_000;
        c.set("self_test", "kpi(1000 pieces, 26/26 days) = " + bonus + " VND in " + micros + " µs");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
