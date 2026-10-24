package io.astra.doctype.store;

import io.astra.spi.doctype.DocTypeDefinition;

import java.util.List;

/**
 * Where a DocType's documents live: the shared {@code astra_doc}, or {@code astra_doc_txn} (partitioned by month)
 * for DocTypes declared with {@code partition: month}. Every SQL statement on documents names the table through
 * here; the two tables have the same columns, indexes and row-level security policy.
 */
public final class DocTables {

    public static final String SHARED = "astra_doc";
    public static final String MONTHLY = "astra_doc_txn";

    private DocTables() { }

    public static String of(DocTypeDefinition def) {
        return def.partitionedByMonth() ? MONTHLY : SHARED;
    }

    public static List<String> all() {
        return List.of(SHARED, MONTHLY);
    }

    /** Prefix of index names on a table: {@code astra_doc_} or {@code astra_doc_txn_}. */
    static String indexPrefix(String table) {
        return table + "_";
    }
}
