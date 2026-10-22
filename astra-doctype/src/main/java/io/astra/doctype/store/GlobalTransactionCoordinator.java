package io.astra.doctype.store;

import java.util.function.Supplier;

/**
 * Kernel side of distributed transactions: begins a global transaction around a document operation and
 * commits or rolls it back with the outcome. The kernel's own database transaction runs inside it and
 * commits last. Implemented by a transaction module (astra-tx-seata); absent means local transactions only.
 */
public interface GlobalTransactionCoordinator {

    boolean available();

    <T> T inGlobal(String name, Supplier<T> work);
}
