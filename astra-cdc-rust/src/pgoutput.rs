//! Decoder for the `pgoutput` logical replication protocol, version 1: the messages PostgreSQL's
//! built-in output plugin emits for a publication (Begin, Commit, Relation, Insert, Update, Delete, …).
//! Each message arrives as one `bytea` row from `pg_logical_slot_peek_binary_changes`.

use anyhow::{Context, Result, bail};

/// Microseconds between the Unix epoch and PostgreSQL's epoch (2000-01-01).
const PG_EPOCH_OFFSET_MICROS: i64 = 946_684_800_000_000;

#[derive(Debug, Clone)]
pub struct Relation {
    pub id: u32,
    pub namespace: String,
    pub name: String,
    pub columns: Vec<String>,
}

/// One column value of a row image. `Unchanged` is a TOASTed value the update did not touch.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Unchanged,
    Text(String),
}

#[derive(Debug)]
pub enum Message {
    Begin { commit_unix_micros: i64 },
    Commit,
    Relation(Relation),
    Insert { relation: u32, new: Vec<Value> },
    Update { relation: u32, new: Vec<Value> },
    /// Replica rows are keyed by (tenant_id, name); a delete's key image carries only the primary key.
    Delete,
    Truncate,
    /// Origin, Type and logical Message records, which the worker does not need.
    Other,
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).filter(|e| *e <= self.buf.len()).context("pgoutput message truncated")?;
        let s = &self.buf[self.pos..end];
        self.pos = end;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8> { Ok(self.take(1)?[0]) }
    fn i16(&mut self) -> Result<i16> { Ok(i16::from_be_bytes(self.take(2)?.try_into()?)) }
    fn u32(&mut self) -> Result<u32> { Ok(u32::from_be_bytes(self.take(4)?.try_into()?)) }
    fn i32(&mut self) -> Result<i32> { Ok(i32::from_be_bytes(self.take(4)?.try_into()?)) }
    fn u64(&mut self) -> Result<u64> { Ok(u64::from_be_bytes(self.take(8)?.try_into()?)) }
    fn i64(&mut self) -> Result<i64> { Ok(i64::from_be_bytes(self.take(8)?.try_into()?)) }
    fn cstr(&mut self) -> Result<String> {
        let rest = &self.buf[self.pos..];
        let len = rest.iter().position(|b| *b == 0).context("unterminated string in pgoutput message")?;
        let s = String::from_utf8(rest[..len].to_vec())?;
        self.pos += len + 1;
        Ok(s)
    }
    fn tuple(&mut self) -> Result<Vec<Value>> {
        let n = self.i16()?;
        let mut out = Vec::with_capacity(n.max(0) as usize);
        for _ in 0..n {
            out.push(match self.u8()? {
                b'n' => Value::Null,
                b'u' => Value::Unchanged,
                b't' => {
                    let len = self.i32()?;
                    Value::Text(String::from_utf8(self.take(len as usize)?.to_vec())?)
                }
                other => bail!("unsupported tuple value kind {:?}", other as char),
            });
        }
        Ok(out)
    }
}

pub fn decode(buf: &[u8]) -> Result<Message> {
    let mut r = Reader { buf, pos: 0 };
    let tag = r.u8()?;
    Ok(match tag {
        b'B' => {
            let _final_lsn = r.u64()?;
            let ts = r.i64()?;
            let _xid = r.u32()?;
            Message::Begin { commit_unix_micros: ts + PG_EPOCH_OFFSET_MICROS }
        }
        b'C' => Message::Commit,
        b'R' => {
            let id = r.u32()?;
            let namespace = r.cstr()?;
            let name = r.cstr()?;
            let _replica_identity = r.u8()?;
            let n = r.i16()?;
            let mut columns = Vec::with_capacity(n.max(0) as usize);
            for _ in 0..n {
                let _flags = r.u8()?;
                columns.push(r.cstr()?);
                let _type_oid = r.u32()?;
                let _type_mod = r.i32()?;
            }
            Message::Relation(Relation { id, namespace, name, columns })
        }
        b'I' => {
            let relation = r.u32()?;
            if r.u8()? != b'N' { bail!("insert without new tuple"); }
            Message::Insert { relation, new: r.tuple()? }
        }
        b'U' => {
            let relation = r.u32()?;
            let mut kind = r.u8()?;
            if kind == b'K' || kind == b'O' {
                r.tuple()?; // old key or old row; replicas are keyed by (tenant_id, name), which the new row carries
                kind = r.u8()?;
            }
            if kind != b'N' { bail!("update without new tuple"); }
            Message::Update { relation, new: r.tuple()? }
        }
        b'D' => Message::Delete,
        b'T' => Message::Truncate,
        _ => Message::Other,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_relation_and_insert() {
        let mut rel = vec![b'R'];
        rel.extend(42u32.to_be_bytes());
        rel.extend(b"public\0astra_doc\0");
        rel.push(b'd');
        rel.extend(2i16.to_be_bytes());
        for name in ["tenant_id", "data"] {
            rel.push(0);
            rel.extend(name.as_bytes());
            rel.push(0);
            rel.extend(25u32.to_be_bytes());
            rel.extend((-1i32).to_be_bytes());
        }
        match decode(&rel).unwrap() {
            Message::Relation(r) => assert_eq!((r.id, r.name.as_str(), r.columns.len()), (42, "astra_doc", 2)),
            m => panic!("unexpected {m:?}"),
        }

        let mut ins = vec![b'I'];
        ins.extend(42u32.to_be_bytes());
        ins.push(b'N');
        ins.extend(2i16.to_be_bytes());
        ins.push(b't');
        ins.extend(4i32.to_be_bytes());
        ins.extend(b"acme");
        ins.push(b'n');
        match decode(&ins).unwrap() {
            Message::Insert { relation, new } => {
                assert_eq!(relation, 42);
                assert_eq!(new, vec![Value::Text("acme".into()), Value::Null]);
            }
            m => panic!("unexpected {m:?}"),
        }
    }
}
