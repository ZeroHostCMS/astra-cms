package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/** An assignment pays an employee by a submitted structure on a positive base, one assignment per start date. */
@ApplicationScoped
public class AssignmentHook implements DocLifecycleHook {

    @Inject DocumentService docs;

    @Override
    public String doctype() { return "SalaryStructureAssignment"; }

    @Override
    public void beforeSave(Document a) {
        a.set("employee_name", EmployeeHook.require(docs, a).getString("employee_name"));
        Values.requireDate(a, "from_date");
        if (Values.vnd(a.get("base")).signum() <= 0) throw new DocValidationException("base must be a positive amount");
        String code = a.getString("salary_structure");
        Document structure = docs.get("SalaryStructure", code)
                .orElseThrow(() -> new DocValidationException("field 'salary_structure': SalaryStructure '" + code + "' not found"));
        if (structure.docstatus() != 1) throw new DocValidationException("salary structure " + code + " is not submitted");
    }

    @Override
    public void onSubmit(Document a) {
        docs.find("SalaryStructureAssignment", Map.of("employee", a.getString("employee"), "docstatus", "1"), 500).stream()
                .filter(other -> a.getString("from_date").equals(other.getString("from_date")))
                .findFirst()
                .ifPresent(other -> {
                    throw new DocValidationException(a.getString("employee") + " already has assignment " + other.name()
                            + " from " + a.getString("from_date") + "; cancel it first");
                });
    }
}
