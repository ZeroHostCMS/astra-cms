package io.astra.server;

import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.release.SlotContract;
import jakarta.enterprise.context.ApplicationScoped;

/** A Tier 1 slot for the rollout tests, outside any app. */
@ApplicationScoped
public class TestSlots implements SlotContract {

    @Override public String slot() { return "test.carrier"; }

    @Override public String tier() { return "isolated-jvm"; }

    @Override public Class<?> spi() { return CarrierAdapter.class; }
}
