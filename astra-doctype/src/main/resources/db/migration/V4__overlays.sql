-- Per-tenant DocType overlays: YAML that adds fields to, or adjusts fields of, an app's DocType for one
-- tenant only. Applied at runtime by the kernel; the app's own definition is never modified.
CREATE TABLE astra_overlay (
    tenant_id   text        NOT NULL,
    doctype     text        NOT NULL,
    yaml        text        NOT NULL,
    version     integer     NOT NULL DEFAULT 1,
    updated_by  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, doctype)
);

ALTER TABLE astra_overlay ENABLE ROW LEVEL SECURITY;
ALTER TABLE astra_overlay FORCE ROW LEVEL SECURITY;
CREATE POLICY astra_overlay_tenant_isolation ON astra_overlay
    USING      (tenant_id = current_setting('astra.tenant', true))
    WITH CHECK (tenant_id = current_setting('astra.tenant', true));
