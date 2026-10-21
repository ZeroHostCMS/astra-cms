package io.astra.spi.doc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A document is one record of a DocType. Core metadata lives in dedicated fields; the DocType's
 * declared fields live in {@link #data()}. Hooks may mutate {@code data} before save.
 */
public final class Document {

    private final String doctype;
    private String name;
    private int docstatus;
    private String owner;
    private Instant createdAt;
    private Instant modifiedAt;
    private int version;
    private String amendedFrom;
    private final Map<String, Object> data;

    public Document(String doctype, Map<String, Object> data) {
        this.doctype = doctype;
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }

    public String doctype() { return doctype; }
    public String name() { return name; }
    public int docstatus() { return docstatus; }
    public String owner() { return owner; }
    public Instant createdAt() { return createdAt; }
    public Instant modifiedAt() { return modifiedAt; }
    public int version() { return version; }
    /** The cancelled document this draft amends, or null. */
    public String amendedFrom() { return amendedFrom; }
    public Map<String, Object> data() { return data; }

    public Object get(String field) { return data.get(field); }
    public String getString(String field) { Object v = data.get(field); return v == null ? null : v.toString(); }
    public Document set(String field, Object value) { data.put(field, value); return this; }

    public Document name(String name) { this.name = name; return this; }
    public Document docstatus(int docstatus) { this.docstatus = docstatus; return this; }
    public Document owner(String owner) { this.owner = owner; return this; }
    public Document createdAt(Instant t) { this.createdAt = t; return this; }
    public Document modifiedAt(Instant t) { this.modifiedAt = t; return this; }
    public Document version(int version) { this.version = version; return this; }
    public Document amendedFrom(String name) { this.amendedFrom = name; return this; }
}
