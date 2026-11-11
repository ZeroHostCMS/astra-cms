package io.astra.kernel.rules;

import io.quarkus.bootstrap.logging.InitialConfigurator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.drools.kiesession.rulebase.InternalKnowledgeBase;
import org.drools.kiesession.rulebase.KnowledgeBaseFactory;
import org.kie.internal.builder.KnowledgeBuilder;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.internal.io.ResourceFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Drools 10.1.0 compiles DRL at run time on this JDK, and a replaced KieBase is collectable. */
public class DroolsSpikeTest {

    /**
     * Without a started Quarkus app the delayed log handler buffers every record, and Drools logs records whose
     * parameters reference the session and its KieBase. Activating the handler, as Quarkus does at startup, drains it.
     */
    @BeforeAll
    static void startLogging() {
        java.util.logging.ConsoleHandler console = new java.util.logging.ConsoleHandler();
        console.setLevel(java.util.logging.Level.WARNING);
        InitialConfigurator.DELAYED_HANDLER.setHandlers(new java.util.logging.Handler[] {console});
    }

    public static class Fact {
        private final String zone;
        private final List<String> lines = new ArrayList<>();
        public Fact(String zone) { this.zone = zone; }
        public String getZone() { return zone; }
        public List<String> getLines() { return lines; }
        public void add(String line) { lines.add(line); }
    }

    private static String drl(String surcharge) {
        return """
                package spike
                import io.astra.kernel.rules.DroolsSpikeTest.Fact
                rule "remote"
                when $f : Fact(zone == "Remote")
                then $f.add("%s");
                end
                """.formatted(surcharge);
    }

    private static KieBase compile(String drl) {
        KnowledgeBuilder builder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        builder.add(ResourceFactory.newByteArrayResource(drl.getBytes(java.nio.charset.StandardCharsets.UTF_8)), ResourceType.DRL);
        if (builder.hasErrors()) throw new IllegalStateException(builder.getErrors().toString());
        InternalKnowledgeBase base = KnowledgeBaseFactory.newKnowledgeBase();
        base.addPackages(builder.getKnowledgePackages());
        return base;
    }

    private static List<String> fire(KieBase base, String zone) {
        KieSession session = base.newKieSession();
        try {
            Fact f = new Fact(zone);
            session.insert(f);
            session.fireAllRules();
            return f.getLines();
        } finally {
            session.dispose();
        }
    }

    @Test
    void recompilesDrlAtRunTimeAndReleasesTheOldBase() throws Exception {
        KieBase v1 = compile(drl("peak-v1"));
        assertEquals(List.of("peak-v1"), fire(v1, "Remote"));
        assertEquals(List.of(), fire(v1, "Domestic"));

        WeakReference<KieBase> old = new WeakReference<>(v1);
        v1 = null;
        KieBase v2 = compile(drl("peak-v2"));
        assertEquals(List.of("peak-v2"), fire(v2, "Remote"));
        KieBase v3 = compile(drl("peak-v3"));
        assertEquals(List.of("peak-v3"), fire(v3, "Remote"));

        List<WeakReference<KieBase>> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            KieBase b = compile(drl("peak-" + i));
            fire(b, "Remote");
            many.add(new WeakReference<>(b));
        }
        for (int i = 0; i < 50; i++) { System.gc(); Thread.sleep(20); }
        assertNull(old.get(), "replaced KieBase still reachable");
        assertEquals(0, many.stream().filter(r -> r.get() != null).count(), "replaced KieBases still reachable");
        System.out.println("drools old KieBase collected");
    }
}
