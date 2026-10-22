package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A structure lists existing components: earnings under {@code earnings}, deductions under {@code deductions},
 * each once. An amount on a row overrides a Fixed component's own amount for this structure.
 */
@ApplicationScoped
public class SalaryStructureHook implements DocLifecycleHook {

    @Inject DocumentService docs;

    @Override
    public String doctype() { return "SalaryStructure"; }

    @Override
    public void beforeSave(Document structure) {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (Values.rows(structure, "earnings").isEmpty()) errors.add("a salary structure needs at least one earning");
        check(structure, "earnings", "Earning", seen, errors);
        check(structure, "deductions", "Deduction", seen, errors);
        if (!errors.isEmpty()) throw new DocValidationException(errors);
    }

    private void check(Document structure, String field, String type, Set<String> seen, List<String> errors) {
        for (Map<String, Object> row : Values.rows(structure, field)) {
            String abbr = row.get("component").toString();
            if (!seen.add(abbr)) errors.add(field + ": component " + abbr + " appears more than once");
            var component = docs.get("SalaryComponent", abbr);
            if (component.isEmpty()) {
                errors.add(field + ": SalaryComponent '" + abbr + "' not found");
                continue;
            }
            if (!type.equals(component.get().getString("component_type"))) {
                errors.add(field + ": " + abbr + " is not an " + type.toLowerCase() + " component");
            }
            if (row.get("amount") != null) {
                if (!"Fixed".equals(component.get().getString("calculation"))) {
                    errors.add(field + ": " + abbr + " is calculated (" + component.get().getString("calculation") + "), its amount cannot be overridden");
                } else if (!(Values.number(row.get("amount")) >= 0)) {
                    errors.add(field + ": amount of " + abbr + " must be a non-negative number");
                }
            }
        }
    }
}
