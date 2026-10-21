-- BMS's own schema, owned by its own role (astra_bms): the credit side of the business, which the
-- kernel's document transaction cannot touch directly. Seata AT branches write here.
CREATE TABLE customer_credit (
    account_key   text           PRIMARY KEY,   -- tenant_id || ':' || customer
    tenant_id     text           NOT NULL,
    customer      text           NOT NULL,
    credit_limit  numeric(18, 2) NOT NULL DEFAULT 0,
    used          numeric(18, 2) NOT NULL DEFAULT 0,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT customer_credit_within_limit CHECK (used >= 0)
);

-- Seata AT undo log (PostgreSQL layout from the Seata distribution): before/after images per branch.
CREATE TABLE undo_log (
    id            bigserial    PRIMARY KEY,
    branch_id     bigint       NOT NULL,
    xid           varchar(128) NOT NULL,
    context       varchar(128) NOT NULL,
    rollback_info bytea        NOT NULL,
    log_status    integer      NOT NULL,
    log_created   timestamp(0) NOT NULL,
    log_modified  timestamp(0) NOT NULL,
    CONSTRAINT ux_undo_log UNIQUE (xid, branch_id)
);
CREATE INDEX ix_undo_log_created ON undo_log (log_created);
