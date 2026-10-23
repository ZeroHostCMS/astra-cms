package io.astra.doctype.store;

import io.astra.spi.doc.DocValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Position in a newest-first list: the {@code modified_at} and {@code name} of the last document returned.
 * Encoded as URL-safe base64 of {@code <epoch micros>:<name>} so clients treat it as opaque.
 */
record ListCursor(Instant modifiedAt, String name) {

    String encode() {
        long micros = modifiedAt.getEpochSecond() * 1_000_000L + modifiedAt.getNano() / 1_000;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + name).getBytes(StandardCharsets.UTF_8));
    }

    static ListCursor parse(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            if (colon <= 0 || colon == raw.length() - 1) throw new IllegalArgumentException("no separator");
            long micros = Long.parseLong(raw.substring(0, colon));
            Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
            return new ListCursor(at, raw.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            throw new DocValidationException("invalid list cursor");
        }
    }
}
