package io.astra.spi.release;

import java.util.Map;

/**
 * What the app behind a slot measures about one release beyond calls and errors, for {@code release_status}: for
 * example, alerts an anomaly detector raised and how many of them people dismissed. Contributed by the app.
 */
public interface ReleaseInsight {

    String slot();

    Map<String, Object> results(String app, String version);
}
