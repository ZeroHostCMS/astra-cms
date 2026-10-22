package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.YearMonth;

/** A one-off amount: positive, for an existing employee, keyed to the month of its payroll date. */
@ApplicationScoped
public class AdditionalSalaryHook implements DocLifecycleHook {

    @Inject DocumentService docs;

    @Override
    public String doctype() { return "AdditionalSalary"; }

    @Override
    public void beforeSave(Document ads) {
        ads.set("employee_name", EmployeeHook.require(docs, ads).getString("employee_name"));
        ads.set("month", YearMonth.from(Values.requireDate(ads, "payroll_date")).toString());
        if (Values.vnd(ads.get("amount")).signum() <= 0) throw new DocValidationException("amount must be positive");
    }
}
