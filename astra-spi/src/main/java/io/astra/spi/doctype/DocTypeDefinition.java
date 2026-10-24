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
        boolean globalTransaction,
        boolean partitionedByMonth) {

    public DocTypeDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("doctype name is required");
        kind = kind == null ? "transaction" : kind;
        naming = naming == null ? Naming.AUTO : naming;
        fields = fields == null ? List.of() : List.copyOf(fields);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        if (partitionedByMonth && !"transaction".equals(kind)) {
            throw new IllegalArgumentException("DocType " + name + ": partition: month is for kind: transaction "
                    + "(master data stays in one table so a lookup by name reads one index)");
        }
    }

    /**
     * Without month partitioning ({@code partition: month}): the document lives in the shared {@code astra_doc}.
     * With it: in {@code astra_doc_txn}, partitioned by the month of {@code created_at}, so old months can be
     * vacuumed, detached and archived on their own. For high-volume transaction DocTypes (orders, waybills).
     */
    public DocTypeDefinition(String name, String module, String kind, boolean submittable, Naming naming,
                             List<FieldDefinition> fields, List<PermissionRule> permissions,
                             boolean segregationOfDuties, boolean globalTransaction) {
        this(name, module, kind, submittable, naming, fields, permissions, segregationOfDuties, globalTransaction, false);
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
