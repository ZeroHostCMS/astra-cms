package io.astra.pluginhost.rollout;

import io.astra.kernel.tenant.TenantRequest;
import io.astra.pluginhost.WasmSandbox;
import io.astra.pluginhost.jvm.IsolatedJvmHost;
import io.astra.pluginhost.jvm.PluginManifest;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.release.SlotContract;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Vets a release when it is saved and starts its rollout when it is approved. Saving records the artifact's SHA-256
 * and who generated it, and vets it the way the slot allows <em>without running untrusted code before approval</em>: a
 * Wasm module or rule set is sandboxed, so its self-test runs on save; a Tier 1 jar runs in the JVM, so saving only
 * reads its manifest (no class is defined, initialised or started) and checks it against the slot's SPI. The jar is
 * loaded and run only when an admin has approved the release and a call is routed to it, so a draft an AI token
 * proposed cannot execute on the server. Submitting (approval, admin only) puts the release on one pod as a canary, or
 * straight to Live when the slot has nothing live in the same scope yet, and supersedes any earlier canary of the slot.
 */
@ApplicationScoped
public class AppReleaseHook implements DocLifecycleHook {

    private static final Logger LOG = Logger.getLogger(AppReleaseHook.class);
    static final String RELEASE_BOT = "release-bot";

    @Inject IsolatedJvmHost jvm;
    @Inject Instance<SlotContract> contracts;
    @Inject RolloutController controller;
    @Inject ReleaseStore store;

    @Override
    public String doctype() { return ReleaseStore.RELEASE; }

    @Override
    public void beforeSave(Document release) {
        String slot = release.getString("slot");
        String tier = release.getString("tier");
        SlotContract contract = contracts.stream().filter(c -> c.slot().equals(slot)).findFirst()
                .orElseThrow(() -> new DocValidationException("unknown slot '" + slot + "'; known: "
                        + contracts.stream().map(SlotContract::slot).sorted().toList()));
        if (!contract.tier().equals(tier)) {
            throw new DocValidationException("slot " + slot + " takes " + contract.tier() + " releases, not " + tier);
        }
        byte[] artifact;
        try {
            artifact = Base64.getDecoder().decode(release.getString("artifact"));
        } catch (IllegalArgumentException e) {
            throw new DocValidationException("artifact must be base64: " + e.getMessage());
        }
        release.set("artifact_sha256", WasmSandbox.sha256(artifact));
        release.set("artifact_bytes", artifact.length);
        if ("isolated-jvm".equals(tier)) {
            // Manifest only: reading the jar's YAML runs none of its code. The jar is loaded after approval (RolloutController).
            PluginManifest manifest;
            try {
                manifest = jvm.manifestOf(artifact);
            } catch (IllegalArgumentException e) {
                throw new DocValidationException("plugin rejected: " + e.getMessage());
            }
            if (contract.spi() != null && !contract.spi().getName().equals(manifest.spi())) {
                throw new DocValidationException("slot " + slot + " needs " + contract.spi().getName() + ", the plugin declares " + manifest.spi());
            }
            release.set("app_id", manifest.id());
            release.set("version", manifest.version());
            release.set("self_test", "manifest vetted (entrypoint " + manifest.entrypoint() + ", network " + manifest.network()
                    + "); loaded in the JVM only after approval");
        } else {
            if (blank(release.getString("app_id")) || blank(release.getString("version"))) {
                throw new DocValidationException("app_id and version are required for " + tier + " releases");
            }
            long started = System.nanoTime();
            String summary = contract.selfTest(artifact);
            release.set("self_test", summary + " in " + (System.nanoTime() - started) / 1_000 + " µs");
        }
        if (release.get("generator") == null) release.set("generator", callerRoles().contains(RELEASE_BOT)
                && !callerRoles().contains("admin") ? "claude-code" : "human");
        if (release.get("stage") == null) release.set("stage", "Draft");
    }

    @Override
    public void onSubmit(Document release) {
        String slot = release.getString("slot");
        String customer = release.getString("customer");
        // Supersede any earlier canary of the same slot and scope, so an older canary can never come back and retire
        // the version approved after it (it would otherwise keep its pods and promote once this one goes Live).
        for (Document other : store.canaries()) {
            if (!other.name().equals(release.name()) && slot.equals(other.getString("slot")) && sameScope(customer, other.getString("customer"))) {
                store.patch(other.name(), java.util.Map.of("stage", "Rolled back", "canary_pods", "",
                        "stage_reason", "superseded by " + release.name()));
                LOG.infof("release %s superseded canary %s on approval", release.name(), other.name());
            }
        }
        boolean nothingLive = store.live(slot).stream().noneMatch(r -> sameScope(customer, r.getString("customer")));
        List<String> pods = store.livePods();
        int total = Math.max(1, pods.size());
        release.set("signed_by", callerUser());
        release.set("calls", 0);
        release.set("errors", 0);
        release.set("p99_ms", 0);
        if (nothingLive) {
            release.set("stage", "Live");
            release.set("canary_pods", "");
            release.set("target_pods", total + "/" + total);
            release.set("stage_reason", "first release of the slot in this scope");
        } else {
            release.set("stage", "Canary");
            release.set("canary_pods", controller.pod());
            release.set("target_pods", "1/" + total);
            release.set("stage_reason", "approved; canary on " + controller.pod());
        }
        LOG.infof("release %s %s@%s slot %s approved by %s: %s", release.name(), release.getString("app_id"),
                release.getString("version"), slot, callerUser(), release.getString("stage_reason"));
        controller.invalidate();
    }

    @Override
    public void onCancel(Document release) {
        release.set("stage", "Retired");
        release.set("canary_pods", "");
        release.set("stage_reason", "withdrawn by " + callerUser());
        controller.invalidate();
    }

    static boolean sameScope(String a, String b) {
        return blank(a) ? blank(b) : a.equals(b);
    }

    private static Set<String> callerRoles() {
        TenantRequest request = request();
        return request == null ? Set.of() : request.roles();
    }

    private static String callerUser() {
        TenantRequest request = request();
        return request == null || request.userId() == null ? "system" : request.userId();
    }

    private static TenantRequest request() {
        return Arc.container().requestContext().isActive() ? Arc.container().instance(TenantRequest.class).get() : null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
