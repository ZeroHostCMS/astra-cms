package io.astra.kernel.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * This kernel instance's name in the cluster: {@code POD_NAME} from the Kubernetes downward API, the host name
 * elsewhere (dev mode, tests). Canary rollout targets pods by this name.
 */
@ApplicationScoped
public class PodIdentity {

    private final String name;

    public PodIdentity(@ConfigProperty(name = "astra.pod-name") Optional<String> configured) {
        this.name = configured.filter(s -> !s.isBlank()).orElseGet(PodIdentity::hostName);
    }

    public String name() { return name; }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
