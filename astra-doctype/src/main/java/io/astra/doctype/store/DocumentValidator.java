package io.astra.doctype.store;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates a document against its DocType: unknown fields, required fields, Select options, defaults. */
final class DocumentValidator {

    private DocumentValidator() { }

    /** Read-only fields are owned by the kernel and hooks; callers may not write them. */
    static void rejectReadOnly(DocTypeDefinition def, Map<String, Object> input) {
        List<String> errors = new ArrayList<>();
        for (String key : input.keySet()) {
            def.field(key).filter(FieldDefinition::readOnly)
                    .ifPresent(f -> errors.add("field '" + f.name() + "' is read-only"));
        }
        if (!errors.isEmpty()) throw new DocValidationException(errors);
    }

    static final int MAX_ATTACHMENT_BYTES = 1 << 20;

    private static void checkAttachment(FieldDefinition f, Object value, List<String> errors) {
        try {
            int size = java.util.Base64.getDecoder().decode(value.toString()).length;
            if (size > MAX_ATTACHMENT_BYTES) errors.add("field '" + f.name() + "' exceeds " + MAX_ATTACHMENT_BYTES + " bytes");
        } catch (IllegalArgumentException e) {
            errors.add("field '" + f.name() + "' must be base64-encoded");
        }
    }

    static void applyDefaultsAndValidate(DocTypeDefinition def, Document doc) {
        List<String> errors = new ArrayList<>();

        for (String key : doc.data().keySet()) {
            if (def.field(key).isEmpty()) errors.add("unknown field '" + key + "' for DocType " + def.name());
        }
        for (FieldDefinition f : def.fields()) {
            Object value = doc.get(f.name());
            if (value == null && f.defaultValue() != null) {
                doc.set(f.name(), f.defaultValue());
                value = f.defaultValue();
            }
            if (f.required() && (value == null || value.toString().isBlank())) {
                errors.add("field '" + f.name() + "' is required");
            }
            if (value != null && f.type() == io.astra.spi.doctype.FieldType.ATTACH) checkAttachment(f, value, errors);
            if (value != null && !f.options().isEmpty() && f.type() == io.astra.spi.doctype.FieldType.SELECT
                    && !f.options().contains(value.toString())) {
                errors.add("field '" + f.name() + "' must be one of " + f.options());
            }
        }
        if (!errors.isEmpty()) throw new DocValidationException(errors);
    }
}
