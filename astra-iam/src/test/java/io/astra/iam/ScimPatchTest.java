package io.astra.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScimPatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScimDirectory.UserInput LAN =
            new ScimDirectory.UserInput("lan", "Lan", "Nguyen", "lan@acme.test", true, null);

    private static List<ScimPatch.Op> ops(String operations) throws Exception {
        JsonNode body = JSON.readTree("{\"schemas\":[\"" + ScimPatch.PATCH_OP + "\"],\"Operations\":" + operations + "}");
        return ScimPatch.parse(body);
    }

    @Test
    void replaceActiveWithPath() throws Exception {
        assertFalse(ScimPatch.applyToUser(LAN, ops("[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]")).active());
    }

    @Test
    void entraStyleCapitalisedOpAndStringBoolean() throws Exception {
        assertFalse(ScimPatch.applyToUser(LAN, ops("[{\"op\":\"Replace\",\"path\":\"active\",\"value\":\"False\"}]")).active());
    }

    @Test
    void oktaStyleValueObjectWithoutPath() throws Exception {
        ScimDirectory.UserInput u = ScimPatch.applyToUser(LAN,
                ops("[{\"op\":\"replace\",\"value\":{\"active\":false,\"name\":{\"givenName\":\"Lân\"},\"nickName\":\"x\"}}]"));
        assertFalse(u.active());
        assertEquals("Lân", u.givenName());
        assertEquals("Nguyen", u.familyName());
    }

    @Test
    void emailFilterPathAndRemove() throws Exception {
        assertEquals("new@acme.test", ScimPatch.applyToUser(LAN,
                ops("[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"].value\",\"value\":\"new@acme.test\"}]")).email());
        assertNull(ScimPatch.applyToUser(LAN, ops("[{\"op\":\"remove\",\"path\":\"emails\"}]")).email());
    }

    @Test
    void unknownPathAndRequiredRemovalAreRejected() {
        ScimException path = assertThrows(ScimException.class,
                () -> ScimPatch.applyToUser(LAN, ops("[{\"op\":\"replace\",\"path\":\"title\",\"value\":\"x\"}]")));
        assertEquals("invalidPath", path.scimType());
        ScimException mut = assertThrows(ScimException.class,
                () -> ScimPatch.applyToUser(LAN, ops("[{\"op\":\"remove\",\"path\":\"userName\"}]")));
        assertEquals("mutability", mut.scimType());
    }

    @Test
    void malformedPatchOpIsInvalidSyntax() throws Exception {
        assertEquals("invalidSyntax", assertThrows(ScimException.class,
                () -> ScimPatch.parse(JSON.readTree("{\"Operations\":[]}"))).scimType());
        assertEquals("invalidSyntax", assertThrows(ScimException.class,
                () -> ops("[{\"op\":\"move\",\"path\":\"active\",\"value\":true}]")).scimType());
        assertEquals("noTarget", assertThrows(ScimException.class, () -> ops("[{\"op\":\"remove\"}]")).scimType());
    }

    @Test
    void groupMembershipOperations() throws Exception {
        Set<String> current = new LinkedHashSet<>(List.of("u1", "u2"));
        assertEquals(Set.of("u1", "u2", "u3"),
                ScimPatch.applyToMembers(current, ops("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"u3\"}]}]")));
        assertEquals(Set.of("u2"),
                ScimPatch.applyToMembers(current, ops("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"u1\\\"]\"}]")));
        assertEquals(Set.of("u1"),
                ScimPatch.applyToMembers(current, ops("[{\"op\":\"remove\",\"path\":\"members\",\"value\":[{\"value\":\"u2\"}]}]")));
        assertEquals(Set.of("u9"),
                ScimPatch.applyToMembers(current, ops("[{\"op\":\"replace\",\"path\":\"members\",\"value\":[{\"value\":\"u9\"}]}]")));
        assertEquals(Set.of(), ScimPatch.applyToMembers(current, ops("[{\"op\":\"remove\",\"path\":\"members\"}]")));
        assertEquals(Set.of("u1", "u2", "u4"),
                ScimPatch.applyToMembers(current, ops("[{\"op\":\"add\",\"value\":{\"members\":[{\"value\":\"u4\"}]}}]")));
    }

    @Test
    void groupNamesAreImmutable() {
        assertEquals("mutability", assertThrows(ScimException.class, () -> ScimPatch.applyToMembers(Set.of(),
                ops("[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"boss\"}]"))).scimType());
    }
}
