package io.astra.doctype.registry;

import com.fasterxml.jackson.databind.JsonNode;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a tenant overlay to an app's DocType. An overlay may add fields and adjust a field's label,
 * required flag, Select options, default and list visibility. It may not remove or retype fields, so
 * documents written before the overlay stay valid and the app's own code keeps working:
 * <pre>
 * doctype: CustomerOrder
 * fields:
 *   add:    [ { name: tax_code, type: Data, label: "Mã số thuế" } ]
 *   modify: [ { name: zone, options: [Same city, Domestic, Remote, Island] } ]
 * </pre>
 */
public final class Overlays {

    private static final Set<String> MODIFIABLE = Set.of("name", "label", "required", "options", "default", "in-list");

    private Overlays() { }

    /** Returns the effective DocType; throws {@link IllegalArgumentException} with a readable reason for a bad overlay. */
    public static DocTypeDefinition apply(DocTypeDefinition base, String yaml) {
        JsonNode root;
        try {
            root = DocTypeLoader.yaml(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("overlay is not valid YAML: " + e.getMessage().lines().findFirst().orElse(""));
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("overlay must be a YAML mapping");
        if (root.hasNonNull("doctype") && !base.name().equals(root.get("doctype").asText())) {
            throw new IllegalArgumentException("overlay is for " + root.get("doctype").asText() + ", not " + base.name());
        }
        List<FieldDefinition> fields = new ArrayList<>(base.fields());
        for (JsonNode m : root.path("fields").path("modify")) {
            String name = m.path("name").asText("");
            int i = indexOf(fields, name);
            if (i < 0) throw new IllegalArgumentException("cannot modify unknown field '" + name + "' of " + base.name());
            m.fieldNames().forEachRemaining(k -> {
                if (!MODIFIABLE.contains(k)) throw new IllegalArgumentException("field property '" + k + "' cannot be changed by an overlay");
            });
            FieldDefinition f = fields.get(i);
            fields.set(i, new FieldDefinition(f.name(), f.type(),
                    m.hasNonNull("label") ? m.get("label").asText() : f.label(),
                    m.has("required") ? m.get("required").asBoolean() : f.required(),
                    f.readOnly(),
                    m.has("in-list") ? m.get("in-list").asBoolean() : f.inList(),
                    f.projected(),
                    m.has("options") ? DocTypeLoader.options(m) : f.options(),
                    m.has("default") ? DocTypeLoader.value(m.get("default")) : f.defaultValue(),
                    f.pii()));
        }
        for (JsonNode a : root.path("fields").path("add")) {
            FieldDefinition f;
            try {
                f = DocTypeLoader.field(a, "overlay of " + base.name());
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            if (indexOf(fields, f.name()) >= 0) throw new IllegalArgumentException("field '" + f.name() + "' already exists in " + base.name());
            if (f.projected() || f.readOnly()) {
                throw new IllegalArgumentException("overlay field '" + f.name() + "' cannot be projected or read-only");
            }
            fields.add(f);
        }
        return new DocTypeDefinition(base.name(), base.module(), base.kind(), base.submittable(), base.naming(),
                fields, base.permissions(), base.segregationOfDuties(), base.globalTransaction());
    }

    /** Names of the fields an overlay adds and modifies, for clients that mark them. */
    public static Map<String, List<String>> touchedFields(String yaml) {
        try {
            JsonNode fields = DocTypeLoader.yaml(yaml).path("fields");
            List<String> added = new ArrayList<>();
            List<String> modified = new ArrayList<>();
            fields.path("add").forEach(f -> added.add(f.path("name").asText()));
            fields.path("modify").forEach(f -> modified.add(f.path("name").asText()));
            return Map.of("added", added, "modified", modified);
        } catch (IOException e) {
            return Map.of("added", List.of(), "modified", List.of());
        }
    }

    private static int indexOf(List<FieldDefinition> fields, String name) {
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).name().equals(name)) return i;
        return -1;
    }
}
