package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;

/** Employee codes look like NV001; someone who left has a relieving date on or after joining. */
@ApplicationScoped
public class EmployeeHook implements DocLifecycleHook {

    @Override
    public String doctype() { return "Employee"; }

    @Override
    public void beforeSave(Document employee) {
        String id = employee.getString("employee_id");
        if (id != null && !id.matches("[A-Z][A-Z0-9-]{1,19}")) {
            throw new DocValidationException("employee_id must be upper-case letters, digits or '-', e.g. NV001");
        }
        LocalDate joined = Values.requireDate(employee, "date_of_joining");
        LocalDate relieved = Values.date(employee, "relieving_date");
        if (relieved != null && relieved.isBefore(joined)) throw new DocValidationException("relieving_date is before date_of_joining");
        if ("Left".equals(employee.getString("status")) && relieved == null) {
            throw new DocValidationException("an employee who left needs a relieving_date");
        }
    }

    /** The employee a document refers to, or a validation error naming the field. */
    static Document require(DocumentService docs, Document doc) {
        String id = doc.getString("employee");
        if (id == null || id.isBlank()) throw new DocValidationException("field 'employee' is required");
        return docs.get("Employee", id).orElseThrow(() -> new DocValidationException("field 'employee': Employee '" + id + "' not found"));
    }
}
