package io.astra.doctype.permission;

import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.spi.doc.Document;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.PermissionRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates DocType permission rules against a user's roles in the current tenant. Rules are unioned:
 * any matching rule that grants an action grants it. No matching rule means no access.
 */
@ApplicationScoped
public class DocPermissions {

    public enum Action { READ, CREATE, WRITE }

    /** What a role set may do with one DocType; {@code write} is {@code all}, {@code own} or {@code none}. */
    public record Effective(boolean read, boolean create, String write) { }

    @Inject DocTypeRegistry registry;

    public void require(String doctype, Set<String> roles, Action action) {
        if (!allows(registry.require(doctype), roles, action, false)) {
            throw new AccessDeniedException(action.name().toLowerCase() + " " + doctype);
        }
    }

    /** Write check for an existing document: rules with {@code if-owner} apply only to the owner. */
    public void requireWrite(String doctype, Set<String> roles, String userId, Document existing) {
        boolean owner = userId != null && userId.equals(existing.owner());
        if (!allows(registry.require(doctype), roles, Action.WRITE, owner)) {
            throw new AccessDeniedException("write " + doctype + "/" + existing.name());
        }
    }

    /** Effective permissions on every registered DocType, for clients that adapt their UI. */
    public Map<String, Effective> effective(Set<String> roles) {
        Map<String, Effective> out = new LinkedHashMap<>();
        for (DocTypeDefinition def : registry.all()) {
            boolean writeAny = allows(def, roles, Action.WRITE, false);
            boolean writeOwn = allows(def, roles, Action.WRITE, true);
            out.put(def.name(), new Effective(
                    allows(def, roles, Action.READ, false),
                    allows(def, roles, Action.CREATE, false),
                    writeAny ? "all" : writeOwn ? "own" : "none"));
        }
        return out;
    }

    private static boolean allows(DocTypeDefinition def, Set<String> roles, Action action, boolean owner) {
        for (PermissionRule rule : def.permissions()) {
            if (!roles.contains(rule.role())) continue;
            boolean granted = switch (action) {
                case READ -> rule.read();
                case CREATE -> rule.create();
                case WRITE -> rule.write() && (!rule.ifOwner() || owner);
            };
            if (granted) return true;
        }
        return false;
    }

    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String what) { super("not permitted: " + what); }
    }
}
