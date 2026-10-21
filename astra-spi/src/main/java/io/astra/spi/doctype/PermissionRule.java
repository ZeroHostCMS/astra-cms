package io.astra.spi.doctype;

/**
 * One permission line of a DocType: what holders of a tenant role may do with its documents.
 * {@code ifOwner} narrows {@code write} to documents the user owns; reads and creates are unaffected.
 * {@code submit} and {@code cancel} govern the lifecycle of submittable DocTypes; amending needs {@code create}.
 * A DocType without rules grants nothing (fail closed).
 */
public record PermissionRule(String role, boolean read, boolean create, boolean write, boolean ifOwner,
                             boolean submit, boolean cancel) {

    public PermissionRule {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("permission role is required");
    }
}
