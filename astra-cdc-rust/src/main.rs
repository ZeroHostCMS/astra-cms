//! Astra CDC worker. Reads the write-ahead log of `astra_doc` through a logical replication slot
//! (pgoutput), projects the configured DocTypes into replica tables, and records how far it got in
//! `_cdc_sync_watermark`, which the kernel's Sync-Aware LSN gate compares against `X-Required-LSN`.
//!
//! Each poll takes the current WAL position, peeks every change committed before it, applies them in
//! one transaction together with the new watermark, then advances the slot. A crash between apply and
//! advance replays those changes; upserts make that harmless (at-least-once, idempotent).

mod pgoutput;

use anyhow::{Context, Result, bail};
use pgoutput::{Message, Relation, Value};
use serde::Deserialize;
use std::collections::HashMap;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio_postgres::{Client, NoTls, Statement};
use tracing::{debug, error, info, warn};

#[derive(Debug, Deserialize)]
struct Config {
    slot: String,
    publication: String,
    #[serde(default = "default_poll_ms")]
    poll_ms: u64,
    routes: Vec<Route>,
}

fn default_poll_ms() -> u64 { 50 }

#[derive(Debug, Deserialize)]
struct Route {
    doctype: String,
    target: String,
    columns: Vec<Column>,
}

#[derive(Debug, Deserialize)]
struct Column {
    name: String,
    from: String,
    transform: Option<String>,
    #[serde(rename = "type")]
    sql_type: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stdout()))
        .init();
    let routes_path = std::env::var("CDC_ROUTES").unwrap_or_else(|_| "cdc-routes.yaml".into());
    let cfg: Config = serde_yaml_ng::from_str(&std::fs::read_to_string(&routes_path).with_context(|| format!("reading {routes_path}"))?)?;
    validate(&cfg)?;
    let url = std::env::var("DATABASE_URL").unwrap_or_else(|_| "postgres://astra_cdc:astra_cdc@localhost:5432/astra".into());
    info!(slot = %cfg.slot, publication = %cfg.publication, routes = cfg.routes.len(), "astra-cdc starting");

    loop {
        tokio::select! {
            result = run(&cfg, &url) => {
                if let Err(e) = result { error!("{e:#}; reconnecting in 2 s"); }
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
            _ = tokio::signal::ctrl_c() => { info!("stopping"); return Ok(()); }
        }
    }
}

/// Routes become SQL identifiers, so they are restricted to plain lower-case names and known types.
fn validate(cfg: &Config) -> Result<()> {
    let ident = |s: &str| !s.is_empty() && s.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_');
    for r in &cfg.routes {
        if !ident(&r.target) { bail!("invalid target table {:?}", r.target); }
        for c in &r.columns {
            if !ident(&c.name) { bail!("invalid column {:?}", c.name); }
            if !matches!(c.sql_type.as_deref(), None | Some("text" | "integer" | "numeric" | "boolean")) {
                bail!("unsupported type {:?} for {}", c.sql_type, c.name);
            }
        }
    }
    Ok(())
}

async fn run(cfg: &Config, url: &str) -> Result<()> {
    let (mut client, connection) = tokio_postgres::connect(url, NoTls).await.context("connecting to PostgreSQL")?;
    tokio::spawn(async move {
        if let Err(e) = connection.await { error!("connection closed: {e}"); }
    });
    prepare_slot(&client, cfg).await?;
    let upserts = prepare_upserts(&client, cfg).await?;
    let mut relations: HashMap<u32, Relation> = HashMap::new();
    let mut last_watermark = Instant::now() - Duration::from_secs(60);
    info!("replicating; watermark in _cdc_sync_watermark");

    loop {
        let cur: String = client.query_one("SELECT pg_current_wal_lsn()::text", &[]).await?.get(0);
        let rows = client
            .query(
                "SELECT lsn::text, data FROM pg_logical_slot_peek_binary_changes($1, $2::text::pg_lsn, NULL, \
                 'proto_version', '1', 'publication_names', $3)",
                &[&cfg.slot, &cur, &cfg.publication],
            )
            .await?;

        let tx = client.transaction().await?;
        let mut applied = 0u64;
        let mut commit_micros = 0i64;
        for row in &rows {
            let lsn: String = row.get(0);
            let data: Vec<u8> = row.get(1);
            match pgoutput::decode(&data)? {
                Message::Relation(r) => { relations.insert(r.id, r); }
                Message::Begin { commit_unix_micros, .. } => commit_micros = commit_unix_micros,
                Message::Insert { relation, new } | Message::Update { relation, new } => {
                    let Some(rel) = relations.get(&relation) else { bail!("change for unknown relation {relation}") };
                    // astra_doc_txn holds DocTypes with `partition: month`; the publication sends its rows under the parent
                    // name (publish_via_partition_root), with the same columns as astra_doc.
                    if rel.namespace != "public" || (rel.name != "astra_doc" && rel.name != "astra_doc_txn") { continue; }
                    if apply(&tx, cfg, &upserts, rel, &new, &lsn).await? { applied += 1; }
                }
                Message::Delete => debug!("delete at {lsn} ignored: documents are never deleted, only cancelled"),
                Message::Truncate => warn!("truncate at {lsn} ignored"),
                Message::Commit | Message::Other => {}
            }
        }
        let lag_ms = if applied > 0 { Some(now_micros().saturating_sub(commit_micros) / 1000) } else { None };
        if applied > 0 || last_watermark.elapsed() >= Duration::from_secs(1) {
            tx.execute(
                "INSERT INTO _cdc_sync_watermark (slot, last_lsn, events, lag_ms, updated_at) VALUES ($1, $2::text::pg_lsn, $3, $4, now()) \
                 ON CONFLICT (slot) DO UPDATE SET last_lsn = excluded.last_lsn, events = _cdc_sync_watermark.events + excluded.events, \
                 lag_ms = coalesce(excluded.lag_ms, _cdc_sync_watermark.lag_ms), updated_at = now()",
                &[&cfg.slot, &cur, &(applied as i64), &lag_ms.map(|v| v as i32)],
            )
            .await?;
            last_watermark = Instant::now();
        }
        tx.commit().await?;
        client.execute("SELECT pg_replication_slot_advance($1, $2::text::pg_lsn)", &[&cfg.slot, &cur]).await?;
        if applied > 0 {
            info!(changes = applied, lsn = %cur, lag_ms = lag_ms.unwrap_or(0), "applied");
        }
        tokio::time::sleep(Duration::from_millis(cfg.poll_ms)).await;
    }
}

async fn prepare_slot(client: &Client, cfg: &Config) -> Result<()> {
    let wal_level: String = client.query_one("SHOW wal_level", &[]).await?.get(0);
    if wal_level != "logical" { bail!("wal_level is {wal_level}; start PostgreSQL with -c wal_level=logical"); }
    if client.query("SELECT 1 FROM pg_publication WHERE pubname = $1", &[&cfg.publication]).await?.is_empty() {
        bail!("publication {} does not exist; run scripts/cdc-setup.sh", cfg.publication);
    }
    if client.query("SELECT 1 FROM pg_replication_slots WHERE slot_name = $1", &[&cfg.slot]).await?.is_empty() {
        client.execute("SELECT pg_create_logical_replication_slot($1, 'pgoutput')", &[&cfg.slot]).await?;
        info!(slot = %cfg.slot, "created logical replication slot");
    }
    Ok(())
}

/// One prepared upsert per route; every value is bound as text and cast to the column's type.
async fn prepare_upserts(client: &Client, cfg: &Config) -> Result<HashMap<String, Statement>> {
    let mut out = HashMap::new();
    for r in &cfg.routes {
        let cols: Vec<&str> = r.columns.iter().map(|c| c.name.as_str()).collect();
        let values: Vec<String> = r.columns.iter().enumerate()
            .map(|(i, c)| format!("${}::text::{}", i + 3, c.sql_type.as_deref().unwrap_or("text")))
            .collect();
        let lsn_param = r.columns.len() + 3;
        let sql = format!(
            "INSERT INTO {t} (tenant_id, name, {cols}, source_lsn, synced_at) VALUES ($1, $2, {vals}, ${lsn_param}::text::pg_lsn, now()) \
             ON CONFLICT (tenant_id, name) DO UPDATE SET {sets}, source_lsn = excluded.source_lsn, synced_at = now()",
            t = r.target,
            cols = cols.join(", "),
            vals = values.join(", "),
            sets = cols.iter().map(|c| format!("{c} = excluded.{c}")).collect::<Vec<_>>().join(", "),
        );
        out.insert(r.doctype.clone(), client.prepare(&sql).await.with_context(|| format!("preparing upsert into {}", r.target))?);
    }
    Ok(out)
}

/// Projects one astra_doc row image into its route's replica table. Returns false when no route matches.
async fn apply(
    tx: &tokio_postgres::Transaction<'_>,
    cfg: &Config,
    upserts: &HashMap<String, Statement>,
    rel: &Relation,
    row: &[Value],
    lsn: &str,
) -> Result<bool> {
    let col = |name: &str| -> Option<&Value> { rel.columns.iter().position(|c| c == name).and_then(|i| row.get(i)) };
    let text = |name: &str| -> Option<String> { match col(name) { Some(Value::Text(s)) => Some(s.clone()), _ => None } };
    let Some(doctype) = text("doctype") else { return Ok(false) };
    let Some(route) = cfg.routes.iter().find(|r| r.doctype == doctype) else { return Ok(false) };
    let (Some(tenant), Some(name)) = (text("tenant_id"), text("name")) else { bail!("astra_doc row without tenant or name") };
    let data: serde_json::Value = match col("data") {
        Some(Value::Text(s)) => serde_json::from_str(s)?,
        Some(Value::Unchanged) => { warn!("{doctype} {name}: data unchanged in WAL record, skipped"); return Ok(false); }
        _ => serde_json::Value::Null,
    };

    let mut values: Vec<Option<String>> = vec![Some(tenant.clone()), Some(name)];
    for c in &route.columns {
        values.push(if c.from == "$docstatus" {
            text("docstatus")
        } else {
            match (data.get(&c.from), c.transform.as_deref()) {
                (Some(serde_json::Value::Array(a)), Some("length")) => Some(a.len().to_string()),
                (Some(serde_json::Value::Null) | None, _) => None,
                (Some(serde_json::Value::String(s)), _) => Some(s.clone()),
                (Some(v), _) => Some(v.to_string()),
            }
        });
    }
    values.push(Some(lsn.to_string()));

    // The replica tables are under row-level security; bind this row's tenant for the write.
    tx.execute("SELECT set_config('astra.tenant', $1, true)", &[&tenant]).await?;
    let params: Vec<&(dyn tokio_postgres::types::ToSql + Sync)> = values.iter().map(|v| v as &(dyn tokio_postgres::types::ToSql + Sync)).collect();
    tx.execute(&upserts[&doctype], &params).await?;
    Ok(true)
}

fn now_micros() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_micros() as i64).unwrap_or(0)
}
