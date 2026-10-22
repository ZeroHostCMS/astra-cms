package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * One attendance mark per employee and day, within their employment. Leave is paid unless marked
 * Leave Without Pay; delivered pieces belong to days actually worked.
 */
@ApplicationScoped
public class AttendanceHook implements DocLifecycleHook {

    @Inject DocumentService docs;

    @Override
    public String doctype() { return "Attendance"; }

    @Override
    public void beforeSave(Document att) {
        Document employee = EmployeeHook.require(docs, att);
        att.set("employee_name", employee.getString("employee_name"));
        LocalDate day = Values.requireDate(att, "attendance_date");
        att.set("month", YearMonth.from(day).toString());
        LocalDate joined = Values.date(employee, "date_of_joining");
        LocalDate relieved = Values.date(employee, "relieving_date");
        if ((joined != null && day.isBefore(joined)) || (relieved != null && day.isAfter(relieved))) {
            throw new DocValidationException(employee.name() + " was not employed on " + day);
        }
        String status = att.getString("status");
        if ("On Leave".equals(status)) {
            if (att.get("leave_type") == null) att.set("leave_type", "Paid Leave");
        } else if (att.get("leave_type") != null) {
            throw new DocValidationException("leave_type applies only to status On Leave");
        }
        double pieces = Values.number(att.get("delivered_pieces"));
        if (!(pieces >= 0) || pieces != Math.floor(pieces)) throw new DocValidationException("delivered_pieces must be a whole number ≥ 0");
        if (pieces > 0 && !("Present".equals(status) || "Half Day".equals(status))) {
            throw new DocValidationException("delivered_pieces need status Present or Half Day");
        }
    }

    @Override
    public void onSubmit(Document att) {
        docs.find("Attendance", Map.of("employee", att.getString("employee"),
                        "attendance_date", att.getString("attendance_date"), "docstatus", "1"), 1).stream()
                .findFirst()
                .ifPresent(other -> {
                    throw new DocValidationException(att.getString("employee") + " is already marked for "
                            + att.getString("attendance_date") + " (" + other.name() + ")");
                });
    }
}
