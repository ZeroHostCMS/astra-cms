package io.astra.apps.hrm;

import io.astra.spi.app.AstraApp;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Astra HRM: employees, attendance and payroll for the courier demo (docs/use-case-01.md), modelled on the
 * standard HR and payroll concepts of open ERP suites (salary components, structures and their assignment,
 * payroll entries that produce salary slips) and written from scratch on the public SPI only.
 */
@ApplicationScoped
public class HrmApp implements AstraApp {

    @Override
    public String id() { return "io.astra.hrm"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public List<String> doctypeResources() {
        return List.of(
                "META-INF/astra/doctypes/employee.doctype.yaml",
                "META-INF/astra/doctypes/salary-component.doctype.yaml",
                "META-INF/astra/doctypes/salary-structure.doctype.yaml",
                "META-INF/astra/doctypes/salary-structure-assignment.doctype.yaml",
                "META-INF/astra/doctypes/attendance.doctype.yaml",
                "META-INF/astra/doctypes/additional-salary.doctype.yaml",
                "META-INF/astra/doctypes/payroll-entry.doctype.yaml",
                "META-INF/astra/doctypes/salary-slip.doctype.yaml");
    }
}
