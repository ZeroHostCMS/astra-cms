package io.astra.spi.doctype;

import java.util.List;
import java.util.Optional;

/**
 * Metadata that fully describes a kind of business record. Everything else (storage, REST, hooks,
 * permissions) derives from this. With {@code segregationOfDuties}, the user who created a document
 * may not submit it (SOX-style maker/checker). With {@code globalTransaction} ({@code transaction: global}),
 * submit and cancel run inside a distributed transaction that hooks can add AT and TCC branches to. Loaded from YAML contributed by apps; immutable once registered.
 */
public record DocTypeDefinition(
        String name,
        String module,
        String kind,
        boolean submittable,
        Naming naming,
        List<FieldDefinition> fields,
        List<PermissionRule> permissions,
        boolean segregationOfDuties,
        boolean globalTransaction) {

    public DocTypeDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("doctype name is required");
        kind = kind == null ? "transaction" : kind;
        naming = naming == null ? Naming.AUTO : naming;
        fields = fields == null ? List.of() : List.copyOf(fields);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public Optional<FieldDefinition> field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
    }

    /**
     * How document names are assigned: from a field value, from a cluster-safe series
     * such as {@code ART-.#####}, or a random identifier.
     *
     * <p>A {@code gapless} series (the default) numbers documents strictly in commit order with no gaps, by
     * locking the series counter until the creating transaction commits; creations of one series in one
     * tenant therefore run one at a time. With {@code gapless: false} each kernel instance takes numbers from
     * a block it reserved in its own short transaction: no lock is held while the document is written, at the
     * price of gaps (unused numbers of a block when an instance stops, rolled-back creations) and of numbers
     * that interleave across instances. Use it for high-volume documents (orders, waybills), not for
     * documents whose numbering must be continuous (accounting vouchers).
     */
    public record Naming(String field, String series, boolean gapless) {
        public static final Naming AUTO = new Naming(null, null);

        public Naming(String field, String series) {
            this(field, series, true);
        }

        public boolean byField() { return field != null && !field.isBlank(); }
        public boolean bySeries() { return series != null && !series.isBlank(); }
    }
}
