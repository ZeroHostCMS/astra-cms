package io.astra.spi.doctype;

import java.util.List;
import java.util.Set;

/**
 * One field of a DocType. {@code options} holds the Select choices or the target DocType for Link/Table.
 * {@code projected} marks fields the storage layer should expose as indexed relational columns.
 * {@code pii} marks personal data (PDPL/GDPR): it is masked wherever data leaves for an AI model.
 *
 * <p>{@code materialised} ({@code projected: materialised} in YAML) goes further: the value is copied into a
 * typed column of its own, so range filters ({@code > < >= <=}) can use an index under row-level security,
 * which an expression on the JSON cannot (casts and {@code numeric} comparisons are not leakproof). Allowed for
 * Currency (stored as bigint hundredths), Int, Float, Date, Datetime and Check.
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
        boolean pii,
        boolean materialised) {

    /** Field types a materialised projection supports. */
    public static final Set<FieldType> MATERIALISABLE =
            Set.of(FieldType.CURRENCY, FieldType.INT, FieldType.FLOAT, FieldType.DATE, FieldType.DATETIME, FieldType.CHECK);

    public FieldDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is required");
        if (type == null) throw new IllegalArgumentException("field type is required for " + name);
        if (materialised && !MATERIALISABLE.contains(type)) {
            throw new IllegalArgumentException("field " + name + ": projected: materialised is for Currency, Int, Float, Date, "
                    + "Datetime and Check; " + type + " fields are projected as an expression index (projected: true)");
        }
        projected = projected || materialised;
        options = options == null ? List.of() : List.copyOf(options);
        label = label == null ? name : label;
    }

    public FieldDefinition(String name, FieldType type, String label, boolean required, boolean readOnly, boolean inList,
                           boolean projected, List<String> options, Object defaultValue, boolean pii) {
        this(name, type, label, required, readOnly, inList, projected, options, defaultValue, pii, false);
    }
}
