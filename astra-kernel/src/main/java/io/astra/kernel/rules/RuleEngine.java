package io.astra.kernel.rules;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.pricing.PricingEngine;
import io.astra.spi.pricing.QuoteLines;
import io.astra.spi.pricing.QuoteRequest;
import io.astra.spi.pricing.RuleQuote;
import io.astra.spi.release.Releases;
import io.astra.spi.script.ScriptException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.drools.kiesession.rulebase.InternalKnowledgeBase;
import org.drools.kiesession.rulebase.KnowledgeBaseFactory;
import org.jboss.logging.Logger;
import org.kie.api.KieBase;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.builder.KnowledgeBuilder;
import org.kie.internal.builder.KnowledgeBuilderError;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.internal.io.ResourceFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drools (Apache KIE 10.1.0) as the Tier 0 price rule engine. A rule set is DRL text: checked by
 * {@link RuleValidator}, compiled once per SHA-256 into a {@link KieBase} (a small LRU keeps the recent ones; a
 * replaced base is dropped and collected), and run per quote in a fresh session on a virtual thread with a deadline
 * and a cap on fired rules. A rule set that fails at run time does not price the order: the caller keeps the
 * previous layer's answer and the release's health counts the failure.
 */
@ApplicationScoped
public class RuleEngine implements PricingEngine {

    private static final Logger LOG = Logger.getLogger(RuleEngine.class);
    static final long DEADLINE_MS = 50;
    /** First fire of a new KieBase builds its network memories; done once at compile time, off the deadline. */
    static final long WARMUP_DEADLINE_MS = 5_000;
    private static final QuoteRequest WARMUP = new QuoteRequest("Remote", "Express", 2.5, 40, 30, 30, 500_000, null, null, null);
    static final int MAX_FIRED = 500;
    private static final int CACHE = 16;

    static {
        // ECJ ships with drools-engine-classic; the server image is a JRE without javax.tools, so use ECJ everywhere.
        if (System.getProperty("drools.dialect.java.compiler") == null) System.setProperty("drools.dialect.java.compiler", "ECLIPSE");
    }

    @Inject Instance<Releases> releases;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Compiled> bases = new LinkedHashMap<>(CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Compiled> eldest) { return size() > CACHE; }
    };

    private record Compiled(KieBase base, int rules) { }

    @Override
    public Optional<RuleQuote> quote(QuoteRequest request) {
        if (!releases.isResolvable()) return Optional.empty();
        Optional<Releases.Artifact> ruleset = releases.get().artifact(SLOT, request.getCustomer());
        if (ruleset.isEmpty()) return Optional.empty();
        Releases.Artifact a = ruleset.get();
        long started = System.nanoTime();
        try {
            RuleQuote q = run(compiled(a.bytes()), request, a.app() + "@" + a.version(), DEADLINE_MS);
            releases.get().record(a, true, System.nanoTime() - started);
            return Optional.of(q);
        } catch (RuntimeException e) {
            releases.get().record(a, false, System.nanoTime() - started);
            throw e;
        }
    }

    @Override
    public RuleQuote quote(byte[] drl, QuoteRequest request) {
        return run(compiled(drl), request, "draft", DEADLINE_MS);
    }

    @Override
    public int validate(byte[] drl) {
        return compiled(drl).rules();
    }

    private Compiled compiled(byte[] drl) {
        String sha = sha256(drl);
        synchronized (bases) {
            Compiled c = bases.get(sha);
            if (c != null) return c;
        }
        String text = new String(drl, StandardCharsets.UTF_8);
        int rules = RuleValidator.check(text);
        KnowledgeBuilder builder = KnowledgeBuilderFactory.newKnowledgeBuilder();
        builder.add(ResourceFactory.newByteArrayResource(drl), ResourceType.DRL);
        if (builder.hasErrors()) {
            List<String> errors = new ArrayList<>();
            for (KnowledgeBuilderError e : builder.getErrors()) {
                if (e.getMessage().contains("Parser returned a null Package")) continue;   // follows every syntax error
                errors.add((e.getLines().length > 0 ? "line " + e.getLines()[0] + ": " : "") + e.getMessage().strip().lines().findFirst().orElse(""));
            }
            throw new DocValidationException(errors);
        }
        InternalKnowledgeBase base = KnowledgeBaseFactory.newKnowledgeBase();
        base.addPackages(builder.getKnowledgePackages());
        Compiled c = new Compiled(base, rules);
        run(c, WARMUP, "warm-up", WARMUP_DEADLINE_MS);
        synchronized (bases) {
            bases.put(sha, c);
        }
        LOG.infof("rule set %s compiled: %d rules", sha.substring(0, 12), rules);
        return c;
    }

    private RuleQuote run(Compiled c, QuoteRequest request, String ruleset, long deadlineMs) {
        QuoteLines lines = new QuoteLines();
        int[] fired = {0};
        Future<?> call = threads.submit(() -> {
            KieSession session = c.base().newKieSession();
            try {
                session.setGlobal("lines", lines);
                session.addEventListener(new DefaultAgendaEventListener() {
                    @Override
                    public void beforeMatchFired(BeforeMatchFiredEvent event) {
                        lines.currentRule(event.getMatch().getRule().getName());
                    }
                });
                session.insert(request);
                fired[0] = session.fireAllRules(MAX_FIRED);
            } finally {
                session.dispose();
            }
        });
        try {
            call.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new ScriptException("timeout", "rule set " + ruleset + " exceeded its " + deadlineMs + " ms deadline", e);
        } catch (ExecutionException e) {
            throw new ScriptException("trap", "rule set " + ruleset + " failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new ScriptException("timeout", "interrupted", e);
        }
        if (fired[0] >= MAX_FIRED) throw new ScriptException("trap", "rule set " + ruleset + " fired " + MAX_FIRED + " rules; stopped", null);
        return new RuleQuote(lines.lines(), lines.getFloor(), ruleset, fired[0]);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
