package io.astra.doctype.sync;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.RequestScoped;

/**
 * The WAL position after the last commit of the current request, for {@code X-Tx-LSN}. The transaction reads it on
 * its own connection right after committing, so the position covers the commit and a write request holds one pooled
 * connection, not two (reading it from a second connection after the response was built doubled connection use per
 * write and, under load, made writes queue behind each other for the pool: measured in docs/benchmark-doctype-jsonb-the-right-way.md).
 */
@RequestScoped
public class CommitLsn {

    private volatile boolean wanted;
    private volatile String lsn;

    /** The request will report {@code X-Tx-LSN}: transactions record the position after they commit. */
    public void want() {
        wanted = true;
    }

    public boolean wanted() {
        return wanted;
    }

    public void record(String position) {
        lsn = position;
    }

    /** The position recorded after the request's last commit, or null when it committed nothing. */
    public String lsn() {
        return lsn;
    }

    /** The current request's holder when a request is active and wants the position, else null. */
    public static CommitLsn ifWanted() {
        if (!Arc.container().requestContext().isActive()) return null;
        CommitLsn holder = Arc.container().instance(CommitLsn.class).get();
        return holder != null && holder.wanted() ? holder : null;
    }
}
