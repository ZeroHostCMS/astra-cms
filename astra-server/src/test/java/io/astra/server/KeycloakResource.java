package io.astra.server;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A real Keycloak, the same version as {@code deploy/keycloak/Dockerfile}, importing every realm file in
 * {@code deploy/keycloak/realm} (the Astra realm and the {@code acme-corp-idp} SAML IdP realm) exactly as
 * the Compose and k3d stacks do. After import it applies what {@code scripts/keycloak-compose-dev.sh} and
 * {@code scripts/keycloak-sync-realm.sh} apply to a dev stack: plain HTTP from the host and SAML broker URLs
 * on the public origin. Astra validates real tokens from it and administers it through the Admin API.
 */
public class KeycloakResource implements QuarkusTestResourceLifecycleManager {

    static final String URL_PROPERTY = "astra.test.keycloak-url";
    private static final String ADMIN_PASSWORD = "admin";

    private GenericContainer<?> keycloak;

    @Override
    public Map<String, String> start() {
        Path deploy = repoRoot().resolve("deploy/keycloak");
        keycloak = new GenericContainer<>(keycloakImage(deploy.resolve("Dockerfile")))
                .withExposedPorts(8080)
                .withEnv("KC_HTTP_RELATIVE_PATH", "/auth")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(deploy.resolve("realm")), "/opt/keycloak/data/import")
                .withCopyFileToContainer(MountableFile.forHostPath(deploy.resolve("themes/astra")), "/opt/keycloak/themes/astra")
                .withCommand("start-dev", "--import-realm")
                .waitingFor(Wait.forHttp("/auth/realms/acme-corp-idp").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));
        keycloak.start();
        String base = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);

        kcadm("config", "credentials", "--server", "http://localhost:8080/auth", "--realm", "master",
                "--user", "admin", "--password", ADMIN_PASSWORD);
        for (String realm : new String[] {"master", "astra", "acme-corp-idp"}) {
            kcadm("update", "realms/" + realm, "-s", "sslRequired=NONE");
        }
        kcadm("update", "identity-provider/instances/acme-corp", "-r", "astra",
                "-s", "config.singleSignOnServiceUrl=" + base + "/auth/realms/acme-corp-idp/protocol/saml",
                "-s", "config.idpEntityId=" + base + "/auth/realms/acme-corp-idp");
        String spClient = kcadm("get", "clients", "-r", "acme-corp-idp", "-q", "clientId=urn:astra:sp",
                "--fields", "id", "--format", "csv", "--noquotes").strip();
        kcadm("update", "clients/" + spClient, "-r", "acme-corp-idp",
                "-s", "redirectUris=[\"" + base + "/auth/realms/astra/broker/acme-corp/endpoint*\"]");

        return Map.of(
                "quarkus.oidc.tenant-enabled", "true",
                "quarkus.oidc.auth-server-url", base + "/auth/realms/astra",
                "quarkus.keycloak.admin-client.server-url", base + "/auth",
                "astra.iam.issuer", base + "/auth/realms/astra",
                URL_PROPERTY, base);
    }

    @Override
    public void stop() {
        if (keycloak != null) keycloak.stop();
    }

    private String kcadm(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "/opt/keycloak/bin/kcadm.sh";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Container.ExecResult r = keycloak.execInContainer(cmd);
            if (r.getExitCode() != 0) {
                throw new IllegalStateException("kcadm " + String.join(" ", args) + " failed: " + r.getStderr() + r.getStdout());
            }
            return r.getStdout();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Path repoRoot() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("deploy/keycloak/realm"))) return p;
        }
        throw new IllegalStateException("deploy/keycloak/realm not found above " + Path.of("").toAbsolutePath());
    }

    private static String keycloakImage(Path dockerfile) {
        try {
            Matcher m = Pattern.compile("(?m)^FROM\\s+(quay\\.io/keycloak/keycloak:\\S+)").matcher(Files.readString(dockerfile));
            if (!m.find()) throw new IllegalStateException("no Keycloak base image in " + dockerfile);
            return m.group(1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
