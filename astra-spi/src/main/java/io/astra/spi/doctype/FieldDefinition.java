package io.astra.spi.doctype;

import java.util.List;

/**
 * One field of a DocType. {@code options} holds the Select choices or the target DocType for Link/Table.
 * {@code projected} marks fields the storage layer should expose as indexed relational columns.
 * {@code pii} marks personal data (PDPL/GDPR): it is masked wherever data leaves for an AI model.
 */
public record FieldDefinition(
        String name,
        FieldType type,
        String label,
        boolean required,
        boolean readOnly,
        boolean inList,
        boolean projected,
        List<String> options,
        Object defaultValue,
        boolean pii) {

    public FieldDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is required");
        if (type == null) throw new IllegalArgumentException("field type is required for " + name);
        options = options == null ? List.of() : List.copyOf(options);
        label = label == null ? name : label;
    }
}
