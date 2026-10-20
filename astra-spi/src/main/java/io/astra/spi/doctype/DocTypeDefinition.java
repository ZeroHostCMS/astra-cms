package io.astra.spi.doctype;

import java.util.List;
import java.util.Optional;

/**
 * Metadata that fully describes a kind of business record. Everything else (storage, REST, hooks)
 * derives from this. Loaded from YAML contributed by apps; immutable once registered.
 */
public record DocTypeDefinition(
        String name,
        String module,
        String kind,
        boolean submittable,
        Naming naming,
        List<FieldDefinition> fields) {

    public DocTypeDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("doctype name is required");
        kind = kind == null ? "transaction" : kind;
        naming = naming == null ? Naming.AUTO : naming;
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public Optional<FieldDefinition> field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
    }

    /**
     * How document names are assigned: from a field value, from a cluster-safe series
     * such as {@code ART-.#####}, or a random identifier.
     */
    public record Naming(String field, String series) {
        public static final Naming AUTO = new Naming(null, null);

        public boolean byField() { return field != null && !field.isBlank(); }
        public boolean bySeries() { return series != null && !series.isBlank(); }
    }
}
