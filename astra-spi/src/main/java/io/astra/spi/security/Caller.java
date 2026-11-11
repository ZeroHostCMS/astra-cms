package io.astra.spi.security;

import java.util.Set;

/** Who is calling, for app code that answers per person (for example "my alerts"): empty outside a signed-in request. */
public interface Caller {

    /** The signed-in user's stable identifier, or {@code null} for background work. */
    String userId();

    /** The user's roles in the current tenant. */
    Set<String> roles();
}
