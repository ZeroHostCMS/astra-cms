package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;

/** A submitted order waits for its electronic invoice; {@link EInvoiceJob} issues it outside the submit transaction. */
@ApplicationScoped
public class EInvoiceHook implements DocLifecycleHook {

    @Override
    public String doctype() { return "CustomerOrder"; }

    @Override
    public void onSubmit(Document order) {
        order.set("einvoice_status", "Pending");
    }
}
