package io.astra.doctype.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;
import io.astra.spi.doctype.FieldType;

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

    private static DocTypeDefinition fromNode(JsonNode root, String resource) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (JsonNode f : root.path("fields")) {
            List<String> options = new ArrayList<>();
            if (f.path("options").isArray()) f.path("options").forEach(o -> options.add(o.asText()));
            else if (f.hasNonNull("options")) options.add(f.get("options").asText());
            fields.add(new FieldDefinition(
                    f.path("name").asText(null),
                    FieldType.valueOf(f.path("type").asText("Data").toUpperCase(Locale.ROOT)),
                    f.path("label").asText(null),
                    f.path("required").asBoolean(false),
                    f.path("read-only").asBoolean(false),
                    f.path("in-list").asBoolean(false),
                    f.path("projected").asBoolean(false),
                    options,
                    f.hasNonNull("default") ? YAML.convertValue(f.get("default"), Object.class) : null));
        }
        JsonNode naming = root.path("naming");
        return new DocTypeDefinition(
                root.path("name").asText(null),
                root.path("module").asText("core"),
                root.path("kind").asText(null),
                root.path("submittable").asBoolean(false),
                naming.isMissingNode() ? DocTypeDefinition.Naming.AUTO
                        : new DocTypeDefinition.Naming(naming.path("field").asText(null), naming.path("series").asText(null)),
                fields);
    }
}
