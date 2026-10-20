package io.astra.spi.doctype;

/** Field types a DocType can declare. Mirrors the vocabulary in the README; extended over time. */
public enum FieldType {
    DATA,       // short text
    TEXT,       // long text
    INT,
    FLOAT,
    CURRENCY,
    CHECK,      // boolean
    DATE,
    DATETIME,
    SELECT,     // one of options
    LINK,       // reference to another DocType by name
    TABLE,      // child table (list of child documents)
    JSON        // free-form object
}
