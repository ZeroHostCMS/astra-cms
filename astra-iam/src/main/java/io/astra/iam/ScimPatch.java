package io.astra.iam;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC 7644 section 3.5.2 PATCH operations, applied to a user's editable attributes or to a group's member
 * set. Operation names and attribute paths are case-insensitive (Microsoft Entra sends {@code "Replace"}
 * and string booleans), and paths may carry the core schema URN prefix. Pure functions: the caller loads
 * the current state and stores the result.
 */
final class ScimPatch {

    static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final Pattern EMAIL_VALUE = Pattern.compile("^emails(\\[[^\\]]*\\])?\\.value$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMBER_BY_VALUE = Pattern.compile(
            "^members\\[\\s*value\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\]$", Pattern.CASE_INSENSITIVE);

    /** One operation: {@code op} is lower-case {@code add}, {@code remove} or {@code replace}; path and value may be null. */
    record Op(String op, String path, JsonNode value) { }

    private ScimPatch() { }

    static List<Op> parse(JsonNode body) {
        if (body == null || !body.isObject()) throw ScimException.invalidSyntax("a PatchOp object is required");
        if (!hasSchema(body, PATCH_OP)) throw ScimException.invalidSyntax("schemas must contain " + PATCH_OP);
        JsonNode ops = body.get("Operations");
        if (ops == null || !ops.isArray() || ops.isEmpty()) throw ScimException.invalidSyntax("Operations must be a non-empty array");
        List<Op> out = new ArrayList<>();
        for (JsonNode o : ops) {
            String op = o.path("op").asText("").toLowerCase(Locale.ROOT);
            if (!Set.of("add", "remove", "replace").contains(op)) {
                throw ScimException.invalidSyntax("op must be add, remove or replace");
            }
            JsonNode path = o.get("path");
            String p = path == null || path.isNull() || path.asText().isBlank() ? null : path.asText().trim();
            JsonNode value = o.get("value");
            if (!op.equals("remove") && (value == null || value.isNull())) {
                throw ScimException.invalidSyntax(op + " requires a value");
            }
            if (op.equals("remove") && p == null) throw ScimException.noTarget("remove requires a path");
            out.add(new Op(op, p, value));
        }
        return out;
    }

    /** Applies the operations to {@code current}; password changes are carried in the result. */
    static ScimDirectory.UserInput applyToUser(ScimDirectory.UserInput current, List<Op> ops) {
        ScimDirectory.UserInput u = current;
        for (Op op : ops) {
            if (op.path() == null) {
                if (!op.value().isObject()) throw ScimException.invalidSyntax(op.op() + " without a path requires an object value");
                for (Map.Entry<String, JsonNode> f : op.value().properties()) {
                    u = setUserAttribute(u, strip(f.getKey(), ScimResource.USER_SCHEMA), f.getValue(), false);
                }
            } else if (op.op().equals("remove")) {
                u = removeUserAttribute(u, strip(op.path(), ScimResource.USER_SCHEMA));
            } else {
                u = setUserAttribute(u, strip(op.path(), ScimResource.USER_SCHEMA), op.value(), true);
            }
        }
        return u;
    }

    /** Applies the operations to the group's current member ids and returns the desired member ids. */
    static Set<String> applyToMembers(Set<String> current, List<Op> ops) {
        Set<String> members = new LinkedHashSet<>(current);
        for (Op op : ops) {
            String path = op.path() == null ? null : strip(op.path(), ScimResource.GROUP_SCHEMA);
            if (path == null) {
                if (!op.value().isObject()) throw ScimException.invalidSyntax(op.op() + " without a path requires an object value");
                for (Map.Entry<String, JsonNode> f : op.value().properties()) {
                    if (f.getKey().equalsIgnoreCase("members")) {
                        if (op.op().equals("replace")) members.clear();
                        members.addAll(memberIds(f.getValue()));
                    } else if (f.getKey().equalsIgnoreCase("displayName")) {
                        throw ScimException.mutability("group names are the tenant's roles and cannot be changed");
                    } else if (!f.getKey().equalsIgnoreCase("schemas") && !f.getKey().equalsIgnoreCase("id")) {
                        throw ScimException.invalidPath("unsupported group attribute " + f.getKey());
                    }
                }
                continue;
            }
            Matcher byValue = MEMBER_BY_VALUE.matcher(path);
            if (op.op().equals("remove") && byValue.matches()) {
                members.remove(unescape(byValue.group(1)));
            } else if (path.equalsIgnoreCase("members")) {
                switch (op.op()) {
                    case "add" -> members.addAll(memberIds(op.value()));
                    case "replace" -> { members.clear(); members.addAll(memberIds(op.value())); }
                    default -> {
                        if (op.value() == null || op.value().isNull()) members.clear();
                        else members.removeAll(memberIds(op.value()));
                    }
                }
            } else if (path.equalsIgnoreCase("displayName")) {
                throw ScimException.mutability("group names are the tenant's roles and cannot be changed");
            } else {
                throw ScimException.invalidPath("unsupported group path " + path);
            }
        }
        return members;
    }

    static boolean hasSchema(JsonNode body, String urn) {
        JsonNode schemas = body.get("schemas");
        if (schemas == null || !schemas.isArray()) return false;
        for (JsonNode s : schemas) if (urn.equalsIgnoreCase(s.asText())) return true;
        return false;
    }

    /** The primary e-mail of a SCIM {@code emails} array, else its first entry; null when empty. */
    static String primaryEmail(JsonNode emails) {
        if (emails == null || emails.isNull()) return null;
        if (!emails.isArray()) throw ScimException.invalidValue("emails must be an array");
        String first = null;
        for (JsonNode e : emails) {
            String v = text(e.get("value"));
            if (v == null) continue;
            if (e.path("primary").asBoolean(false)) return v;
            if (first == null) first = v;
        }
        return first;
    }

    static Boolean bool(JsonNode v, String attribute) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.booleanValue();
        if (v.isTextual() && (v.asText().equalsIgnoreCase("true") || v.asText().equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(v.asText().toLowerCase(Locale.ROOT));
        }
        throw ScimException.invalidValue(attribute + " must be a boolean");
    }

    static String text(JsonNode v) {
        return v == null || v.isNull() ? null : v.asText();
    }

    private static ScimDirectory.UserInput setUserAttribute(ScimDirectory.UserInput u, String path, JsonNode v, boolean explicitPath) {
        String p = path.toLowerCase(Locale.ROOT);
        if (EMAIL_VALUE.matcher(path).matches()) return u.withEmail(text(v));
        return switch (p) {
            case "active" -> u.withActive(bool(v, "active"));
            case "username" -> {
                String name = text(v);
                if (name == null || name.isBlank()) throw ScimException.invalidValue("userName must not be empty");
                yield u.withUserName(name);
            }
            case "name" -> {
                if (!v.isObject()) throw ScimException.invalidValue("name must be an object");
                ScimDirectory.UserInput r = u;
                if (v.has("givenName")) r = r.withGivenName(text(v.get("givenName")));
                if (v.has("familyName")) r = r.withFamilyName(text(v.get("familyName")));
                yield r;
            }
            case "name.givenname" -> u.withGivenName(text(v));
            case "name.familyname" -> u.withFamilyName(text(v));
            case "emails" -> u.withEmail(primaryEmail(v));
            case "password" -> u.withPassword(text(v));
            case "displayname", "externalid", "schemas", "id" -> u;   // derived, unsupported or read-only: ignored
            default -> {
                if (explicitPath) throw ScimException.invalidPath("unsupported user path " + path);
                yield u;   // like POST and PUT, unknown attributes in a value object are ignored
            }
        };
    }

    private static ScimDirectory.UserInput removeUserAttribute(ScimDirectory.UserInput u, String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (EMAIL_VALUE.matcher(path).matches()) return u.withEmail(null);
        return switch (p) {
            case "name.givenname" -> u.withGivenName(null);
            case "name.familyname" -> u.withFamilyName(null);
            case "name" -> u.withGivenName(null).withFamilyName(null);
            case "emails" -> u.withEmail(null);
            case "username", "active" -> throw ScimException.mutability(path + " cannot be removed");
            default -> throw ScimException.invalidPath("unsupported user path " + path);
        };
    }

    private static Set<String> memberIds(JsonNode value) {
        if (value == null || !value.isArray()) throw ScimException.invalidValue("members must be an array of {\"value\": id}");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode m : value) {
            String id = text(m.get("value"));
            if (id == null || id.isBlank()) throw ScimException.invalidValue("every member needs a value (the user id)");
            ids.add(id);
        }
        return ids;
    }

    private static String strip(String path, String schemaUrn) {
        String prefix = schemaUrn + ":";
        return path.regionMatches(true, 0, prefix, 0, prefix.length()) ? path.substring(prefix.length()) : path;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
