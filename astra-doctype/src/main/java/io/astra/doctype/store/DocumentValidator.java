package io.astra.doctype.store;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;

import java.util.ArrayList;
import java.util.List;

/** Validates a document against its DocType: unknown fields, required fields, Select options, defaults. */
final class DocumentValidator {

    private DocumentValidator() { }

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
            if (value != null && !f.options().isEmpty() && f.type() == io.astra.spi.doctype.FieldType.SELECT
                    && !f.options().contains(value.toString())) {
                errors.add("field '" + f.name() + "' must be one of " + f.options());
            }
        }
        if (!errors.isEmpty()) throw new DocValidationException(errors);
    }
}
