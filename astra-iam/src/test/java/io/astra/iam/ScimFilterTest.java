package io.astra.iam;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScimFilterTest {

    private static final String USER = ScimResource.USER_SCHEMA;

    @Test
    void parsesUserNameEquality() {
        ScimFilter f = ScimFilter.parse("userName eq \"Lan.Nguyen\"", USER, "userName").orElseThrow();
        assertEquals("Lan.Nguyen", f.value());
        assertTrue(f.matches("lan.nguyen"), "userName is case-insensitive");
    }

    @Test
    void acceptsCaseInsensitiveOperatorAttributeAndSchemaPrefix() {
        assertEquals("a\"b", ScimFilter.parse(USER + ":USERNAME EQ \"a\\\"b\"", USER, "userName").orElseThrow().value());
    }

    @Test
    void absentFilterMeansNoFilter() {
        assertEquals(Optional.empty(), ScimFilter.parse(null, USER, "userName"));
        assertEquals(Optional.empty(), ScimFilter.parse("  ", USER, "userName"));
    }

    @Test
    void rejectsOtherOperatorsAttributesAndExpressions() {
        for (String bad : new String[] {"userName co \"a\"", "emails eq \"a\"", "userName eq \"a\" and active eq true",
                "userName pr", "userName eq a"}) {
            ScimException e = assertThrows(ScimException.class, () -> ScimFilter.parse(bad, USER, "userName"), bad);
            assertEquals(400, e.status());
            assertEquals("invalidFilter", e.scimType());
        }
    }
}
