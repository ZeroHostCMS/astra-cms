package io.astra.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subset of the RFC 7644 filter grammar this server answers: a single {@code <attribute> eq "<value>"}
 * comparison, which is what provisioning clients send to look a resource up before creating it. The
 * attribute may carry the core schema URN prefix. Anything else is rejected with {@code invalidFilter}.
 */
public record ScimFilter(String attribute, String value) {

    private static final Pattern EQ = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9_.:-]*)\\s+([A-Za-z]+)\\s+(\"(?:[^\"\\\\]|\\\\.)*\")\\s*$");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Parses {@code filter}; an absent or blank filter yields empty.
     *
     * @param schemaUrn the resource's core schema URN, accepted as an attribute prefix
     * @param allowed   the attribute that may be compared, matched case-insensitively
     */
    public static Optional<ScimFilter> parse(String filter, String schemaUrn, String allowed) {
        if (filter == null || filter.isBlank()) return Optional.empty();
        Matcher m = EQ.matcher(filter);
        if (!m.matches()) {
            throw ScimException.invalidFilter("only filters of the form " + allowed + " eq \"value\" are supported");
        }
        String attribute = m.group(1);
        String prefix = schemaUrn + ":";
        if (attribute.regionMatches(true, 0, prefix, 0, prefix.length())) attribute = attribute.substring(prefix.length());
        if (!m.group(2).equalsIgnoreCase("eq")) {
            throw ScimException.invalidFilter("operator " + m.group(2) + " is not supported; use eq");
        }
        if (!attribute.equalsIgnoreCase(allowed)) {
            throw ScimException.invalidFilter("filtering on " + attribute + " is not supported; use " + allowed);
        }
        try {
            return Optional.of(new ScimFilter(allowed, JSON.readValue(m.group(3), String.class)));
        } catch (JsonProcessingException e) {
            throw ScimException.invalidFilter("malformed string literal in filter");
        }
    }

    /** SCIM {@code userName} and {@code displayName} are case-insensitive ({@code caseExact: false}). */
    public boolean matches(String candidate) {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).equals(value.toLowerCase(Locale.ROOT));
    }
}
