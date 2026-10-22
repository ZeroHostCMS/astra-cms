package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads what one salary slip needs, in the caller's transaction: the assignment in force at the end of the
 * period, its submitted structure and components, and the submitted attendance and additional salaries
 * dated inside the period. The calculation that follows touches no storage.
 */
@ApplicationScoped
public class PayrollInputs {

    /** Everything a slip is computed from; immutable snapshots, safe to hand to another thread. */
    record SalaryInput(Document employee, Document assignment, Document structure, Map<String, Document> components,
                       List<Document> attendance, List<Document> additional, LocalDate start, LocalDate end) { }

    @Inject DocumentService docs;

    /** Empty when no submitted assignment has started by the end of the period. */
    Optional<SalaryInput> load(Document employee, LocalDate start, LocalDate end) {
        Optional<Document> assignment = docs.find("SalaryStructureAssignment",
                        Map.of("employee", employee.name(), "docstatus", "1"), 500).stream()
                .filter(a -> !Values.requireDate(a, "from_date").isAfter(end))
                .max(Comparator.comparing(a -> Values.requireDate(a, "from_date")));
        if (assignment.isEmpty()) return Optional.empty();

        String code = assignment.get().getString("salary_structure");
        Document structure = docs.get("SalaryStructure", code).filter(s -> s.docstatus() == 1)
                .orElseThrow(() -> new DocValidationException("salary structure " + code + " of " + employee.name() + " is not submitted"));
        List<Document> attendance = inPeriod("Attendance", "attendance_date", employee, start, end);
        List<Document> additional = inPeriod("AdditionalSalary", "payroll_date", employee, start, end);

        Map<String, Document> components = new LinkedHashMap<>();
        Stream.concat(Stream.concat(Values.rows(structure, "earnings").stream(), Values.rows(structure, "deductions").stream())
                                .map(r -> r.get("component").toString()),
                        additional.stream().map(a -> a.getString("salary_component")))
                .forEach(abbr -> components.computeIfAbsent(abbr, k -> docs.get("SalaryComponent", k)
                        .orElseThrow(() -> new DocValidationException("SalaryComponent '" + k + "' not found"))));
        return Optional.of(new SalaryInput(employee, assignment.get(), structure, components, attendance, additional, start, end));
    }

    /** Submitted documents of an employee dated within the period, looked up by the projected month key. */
    private List<Document> inPeriod(String doctype, String dateField, Document employee, LocalDate start, LocalDate end) {
        return Values.months(start, end).stream()
                .flatMap(m -> docs.find(doctype, Map.of("employee", employee.name(), "month", m, "docstatus", "1"), 1000).stream())
                .filter(d -> {
                    LocalDate day = Values.requireDate(d, dateField);
                    return !day.isBefore(start) && !day.isAfter(end);
                })
                .toList();
    }
}
