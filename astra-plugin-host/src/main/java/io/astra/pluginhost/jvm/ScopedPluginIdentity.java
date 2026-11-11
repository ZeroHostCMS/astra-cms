package io.astra.pluginhost.jvm;

import java.util.Optional;

/** {@code id@version} of the Tier 1 plugin running on the current thread, so capabilities and audit know who calls. */
public final class ScopedPluginIdentity {

    static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    private ScopedPluginIdentity() { }

    public static Optional<String> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
