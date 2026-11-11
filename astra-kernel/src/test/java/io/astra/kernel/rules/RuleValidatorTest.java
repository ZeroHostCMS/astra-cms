package io.astra.kernel.rules;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.pricing.QuoteRequest;
import io.astra.spi.pricing.RuleQuote;
import io.astra.spi.script.ScriptException;
import io.quarkus.bootstrap.logging.InitialConfigurator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The allowlist in front of rules-as-data, and the engine that runs what passes it. */
class RuleValidatorTest {

    private static final String HEADER = """
            package t
            import io.astra.spi.pricing.QuoteRequest
            global io.astra.spi.pricing.QuoteLines lines
            """;
    private static final QuoteRequest REMOTE = new QuoteRequest("Remote", "Express", 2.0, 0, 0, 0, 500_000, "C1", "B2B", LocalDate.of(2026, 11, 11));

    @BeforeAll
    static void startLogging() {
        InitialConfigurator.DELAYED_HANDLER.setHandlers(new java.util.logging.Handler[] {new java.util.logging.ConsoleHandler()});
    }

    private static String rule(String when, String then) {
        return HEADER + "rule \"r\"\nwhen\n    " + when + "\nthen\n    " + then + "\nend\n";
    }

    private static byte[] bytes(String drl) {
        return drl.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aPlainRuleSetPassesAndPricesWithLinesThatNameTheirRule() {
        String drl = rule("QuoteRequest(zone == \"Remote\", $kg : weightKg)", "lines.add(\"base\", \"Cước\", 45000 + 7000 * (long) Math.ceil(Math.max(0, $kg - 1) / 0.5));")
                + "rule \"lock-it\"\nsalience -1\nwhen\n    QuoteRequest(month == 11, day >= 10, day <= 12)\nthen\n    lines.add(\"peak\", \"11.11\", 5000);\n    lines.lock(\"peak\");\n    lines.floor(20000);\nend\n";
        RuleEngine engine = new RuleEngine();
        assertEquals(2, engine.validate(bytes(drl)));
        RuleQuote q = engine.quote(bytes(drl), REMOTE);
        assertEquals(List.of("base", "peak"), q.lines().stream().map(l -> l.code()).toList());
        assertEquals(59_000, q.lines().get(0).amount());   // 45,000 + 2 × 7,000 for the second kg
        assertEquals("rule:r", q.lines().get(0).source());
        assertTrue(q.lines().get(1).locked());
        assertEquals(20_000, q.floor());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "System.exit(0);",
            "java.lang.System.exit(0);",
            "Runtime.getRuntime().exec(\"rm -rf /\");",
            "Class.forName(\"x\");",
            "lines.getClass().getClassLoader();",
            "new java.io.File(\"/etc/passwd\").delete();",
            "drools.halt();",
            "kcontext.getKieRuntime();",
            "insert(lines);",
            "Thread.sleep(10000);",
            "lines.add(\"x\", \"\\u0022); System.exit(0); //\", 1);",
            // a char literal '"' made the string blanker and the Java compiler disagree on where a string starts
            "lines.floor('\"');new java.io.File(new String(new char[]{47,116,109,112})).createNewFile();lines.floor('\"');",
            "lines.floor('\"');java.lang.Runtime.getRuntime().exec(new String[]{});lines.floor('\"');",
            "lines.floor('\"');long $i = 0; while (true) { $i++; } lines.floor('\"');",
            "lines.add(\"x\", 'y', 1);",
            "lines.add(\"x\", \"\"\"\nSystem\"\"\", 1);",
    })
    void dangerousConsequencesAreRefusedOnSave(String then) {
        DocValidationException e = assertThrows(DocValidationException.class, () -> RuleValidator.check(rule("QuoteRequest()", then)));
        assertTrue(e.getMessage().contains("not allowed"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "QuoteRequest(zone matches \"(a+)+$\")",
            "QuoteRequest(eval(System.currentTimeMillis() > 0))",
            "QuoteRequest() from System.getenv()",
            "QuoteRequest(customer.class.name == \"x\")",
            "QuoteRequest(date.getClass() != null)",
    })
    void dangerousConditionsAreRefusedOnSave(String when) {
        assertThrows(DocValidationException.class, () -> RuleValidator.check(rule(when, "lines.add(\"x\", \"x\", 1);")));
    }

    @Test
    void importsGlobalsFunctionsAndQueriesOutsideTheModelAreRefused() {
        assertThrows(DocValidationException.class, () -> RuleValidator.check(HEADER.replace("QuoteRequest\n", "QuoteRequest\nimport java.io.File\n")
                + "rule \"r\" when QuoteRequest() then lines.add(\"x\",\"x\",1); end\n"));
        assertThrows(DocValidationException.class, () -> RuleValidator.check(HEADER + "global java.util.List leak\n"
                + "rule \"r\"\nwhen\n QuoteRequest()\nthen\n lines.add(\"x\",\"x\",1);\nend\n"));
        assertThrows(DocValidationException.class, () -> RuleValidator.check(HEADER + "function void boom() { System.exit(0); }\n"));
        assertThrows(DocValidationException.class, () -> RuleValidator.check(HEADER + "query q() QuoteRequest() end\n"));
        assertThrows(DocValidationException.class, () -> RuleValidator.check(rule("QuoteRequest()", "lines.add(\"x\",\"x\",1);")
                .replace("rule \"r\"", "rule \"r\" dialect \"mvel\"")));
    }

    @Test
    void aSyntaxErrorIsRefusedWithItsLine() {
        DocValidationException e = assertThrows(DocValidationException.class,
                () -> new RuleEngine().validate(bytes(rule("QuoteRequest(zone == \"Remote\"", "lines.add(\"x\", \"x\", 1);"))));
        assertTrue(e.getMessage().contains("line"), e.getMessage());
    }

    @Test
    void aRuleThatFailsAtRunTimeIsATrapNotAPrice() {
        String twice = rule("QuoteRequest()", "lines.add(\"base\", \"a\", 1);\n    lines.add(\"base\", \"b\", 2);");
        ScriptException e = assertThrows(ScriptException.class, () -> new RuleEngine().quote(bytes(twice), REMOTE));
        assertEquals("trap", e.reason());
        assertTrue(e.getMessage().contains("added twice"), e.getMessage());
    }
}
