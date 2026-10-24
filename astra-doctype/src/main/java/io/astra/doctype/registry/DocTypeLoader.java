package io.astra.doctype.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;
import io.astra.spi.doctype.FieldType;
import io.astra.spi.doctype.PermissionRule;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses a DocType YAML resource into an immutable {@link DocTypeDefinition}. */
final class DocTypeLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private DocTypeLoader() { }

    static DocTypeDefinition load(ClassLoader cl, String resource) {
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("DocType resource not found: " + resource);
            return fromNode(YAML.readTree(in), resource);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read DocType " + resource, e);
        }
    }

    /** One field declaration, as written in a DocType or an overlay. */
    static FieldDefinition field(JsonNode f, String where) {
        String fieldName = f.path("name").asText("");
        if (!fieldName.matches("[a-z][a-z0-9_]{0,50}")) {
            throw new IllegalStateException(where + ": field name '" + fieldName + "' must be snake_case");
        }
        JsonNode projected = f.path("projected");
        boolean materialised = projected.isTextual() && "materialised".equals(projected.asText());
        if (projected.isTextual() && !materialised) {
            throw new IllegalStateException(where + ": field '" + fieldName + "': projected must be true, false or materialised");
        }
        try {
            return new FieldDefinition(
                    fieldName,
                    FieldType.valueOf(f.path("type").asText("Data").toUpperCase(Locale.ROOT)),
                    f.path("label").asText(null),
                    f.path("required").asBoolean(false),
                    f.path("read-only").asBoolean(false),
                    f.path("in-list").asBoolean(false),
                    materialised || projected.asBoolean(false),
                    options(f),
                    f.hasNonNull("default") ? YAML.convertValue(f.get("default"), Object.class) : null,
                    f.path("pii").asBoolean(false),
                    materialised);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": " + e.getMessage());
        }
    }

    static List<String> options(JsonNode f) {
        List<String> options = new ArrayList<>();
        if (f.path("options").isArray()) f.path("options").forEach(o -> options.add(o.asText()));
        else if (f.hasNonNull("options")) options.add(f.get("options").asText());
        return options;
    }

    static Object value(JsonNode node) {
        return YAML.convertValue(node, Object.class);
    }

    static JsonNode yaml(String text) throws IOException {
        return YAML.readTree(text);
    }

    private static DocTypeDefinition fromNode(JsonNode root, String resource) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (JsonNode f : root.path("fields")) fields.add(field(f, "DocType " + resource));
        List<PermissionRule> permissions = new ArrayList<>();
        for (JsonNode p : root.path("permissions")) {
            permissions.add(new PermissionRule(
                    p.path("role").asText(null),
                    p.path("read").asBoolean(false),
                    p.path("create").asBoolean(false),
                    p.path("write").asBoolean(false),
                    p.path("if-owner").asBoolean(false),
                    p.path("submit").asBoolean(false),
                    p.path("cancel").asBoolean(false)));
        }
        JsonNode naming = root.path("naming");
        String partition = root.path("partition").asText("none");
        if (!partition.equals("none") && !partition.equals("month")) {
            throw new IllegalStateException("DocType " + resource + ": partition must be month or none, not " + partition);
        }
        try {
            return new DocTypeDefinition(
                    root.path("name").asText(null),
                    root.path("module").asText("core"),
                    root.path("kind").asText(null),
                    root.path("submittable").asBoolean(false),
                    naming.isMissingNode() ? DocTypeDefinition.Naming.AUTO
                            : new DocTypeDefinition.Naming(naming.path("field").asText(null), naming.path("series").asText(null),
                                    naming.path("gapless").asBoolean(true)),
                    fields,
                    permissions,
                    root.path("segregation-of-duties").asBoolean(false),
                    "global".equals(root.path("transaction").asText("local")),
                    partition.equals("month"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(resource + ": " + e.getMessage());
        }
    }
}
