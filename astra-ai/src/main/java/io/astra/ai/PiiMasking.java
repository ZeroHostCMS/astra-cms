package io.astra.ai;

import io.astra.doctype.registry.DocTypeOverlays;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Masks fields declared {@code pii: true} before data leaves for an AI model (PDPL 91/2025/QH15, GDPR
 * data minimisation). Documents are recognised as maps carrying {@code doctype} and {@code data}.
 * Phone numbers keep their last three digits; other text keeps the first letter of each word.
 */
@ApplicationScoped
public class PiiMasking {

    @Inject DocTypeOverlays doctypes;

    public Object mask(Object value) {
        if (value instanceof List<?> list) return list.stream().map(this::mask).toList();
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), mask(v)));
        if (out.get("doctype") instanceof String doctype && out.get("data") instanceof Map<?, ?> data) {
            DocTypeDefinition def = doctypes.effective(doctype);
            Map<String, Object> masked = new LinkedHashMap<>();
            data.forEach((k, v) -> masked.put(String.valueOf(k), isPii(def, String.valueOf(k)) ? maskValue(v) : v));
            out.put("data", masked);
        }
        return out;
    }

    /** Field names that this DocType marks as personal data. */
    public List<String> piiFields(DocTypeDefinition def) {
        List<String> out = new ArrayList<>();
        for (FieldDefinition f : def.fields()) if (f.pii()) out.add(f.name());
        return out;
    }

    private static boolean isPii(DocTypeDefinition def, String field) {
        return def.field(field).map(FieldDefinition::pii).orElse(false);
    }

    static Object maskValue(Object v) {
        if (v == null) return null;
        String s = v.toString();
        String digits = s.replaceAll("\\D", "");
        if (digits.length() >= 8 && digits.length() >= s.replaceAll("\\s", "").length() - 2) {
            return "*".repeat(digits.length() - 3) + digits.substring(digits.length() - 3);
        }
        return Arrays.stream(s.trim().split("\\s+"))
                .map(w -> w.isEmpty() ? w : w.substring(0, w.offsetByCodePoints(0, 1)) + "***")
                .collect(Collectors.joining(" "));
    }
}
