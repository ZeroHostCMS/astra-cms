//! Plugin development kit for Astra customer pricing (slot `pricing.customer`, Tier 2 Wasm).
//!
//! A customer's pricing plugin inherits the price the rule layer computed. It never prices from scratch: it
//! receives the rule layer's lines (`base`, `express`, `cod_fee`, `peak`, …) and answers with operations on them:
//! scale a line, set it, remove it, or add a new one, each with the contract clause it implements. Lines it does
//! not mention stay as the rule layer computed them. The host refuses operations on locked lines, on unknown codes,
//! or that bring the total under the rule layer's floor; then the whole answer is dropped and the rule price stands.
//!
//! A plugin is one function:
//!
//! ```ignore
//! use astra_pricing_pdk::{export_adjust, Op, Quote};
//! fn adjust(q: &Quote) -> Vec<Op> { vec![Op::scale("base", 920, "volume tier 501-2000")] }
//! export_adjust!(adjust);
//! ```
//!
//! Wire format (handled here, never by the plugin): the host writes UTF-8 lines into the module's memory,
//! `ctx` with `key=value` fields, then one `line` per price line (`line<TAB>code<TAB>amount<TAB>locked<TAB>source<TAB>label`);
//! the plugin answers one operation per line (`scale|set|remove|add<TAB>…`).
#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;

pub use alloc::vec;

/// One price line of the rule layer.
pub struct Line<'a> {
    pub code: &'a str,
    pub amount: i64,
    pub locked: bool,
    pub source: &'a str,
    pub label: &'a str,
}

/// The parcel, the customer's profile and the rule layer's lines.
pub struct Quote<'a> {
    pub zone: &'a str,
    pub service: &'a str,
    pub weight_g: i64,
    /// Volumetric weight L×W×H/6000 in grams; 0 when the parcel has no dimensions.
    pub volumetric_g: i64,
    pub cod: i64,
    pub month: i64,
    pub day: i64,
    /// Orders of this customer in the last 30 days.
    pub monthly_orders: i64,
    /// Share of this customer's parcels returned in the last 30 days, in basis points (310 = 3.10 %).
    pub return_rate_bp: i64,
    /// The rule layer's floor under the total.
    pub floor: i64,
    pub lines: Vec<Line<'a>>,
}

impl Quote<'_> {
    pub fn has(&self, code: &str) -> bool { self.lines.iter().any(|l| l.code == code) }
    pub fn amount(&self, code: &str) -> i64 { self.lines.iter().find(|l| l.code == code).map_or(0, |l| l.amount) }
    pub fn total(&self) -> i64 { self.lines.iter().map(|l| l.amount).sum() }
    pub fn express(&self) -> bool { self.service == "Express" }
}

/// An operation on the rule layer's lines, with the contract clause it implements.
pub enum Op {
    /// Multiply a line by `permille` / 1000 (920 = −8 %).
    Scale { code: &'static str, permille: i64, reason: &'static str },
    Set { code: &'static str, amount: i64, reason: &'static str },
    Remove { code: &'static str, reason: &'static str },
    Add { code: &'static str, label: &'static str, amount: i64, reason: &'static str },
}

impl Op {
    pub fn scale(code: &'static str, permille: i64, reason: &'static str) -> Op { Op::Scale { code, permille, reason } }
    pub fn set(code: &'static str, amount: i64, reason: &'static str) -> Op { Op::Set { code, amount, reason } }
    pub fn remove(code: &'static str, reason: &'static str) -> Op { Op::Remove { code, reason } }
    pub fn add(code: &'static str, label: &'static str, amount: i64, reason: &'static str) -> Op { Op::Add { code, label, amount, reason } }
}

/// Parses the host's input. Unknown `ctx` keys are ignored so the host can add fields without breaking plugins.
pub fn parse(input: &str) -> Quote<'_> {
    let mut q = Quote { zone: "", service: "", weight_g: 0, volumetric_g: 0, cod: 0, month: 0, day: 0, monthly_orders: 0,
        return_rate_bp: 0, floor: 0, lines: Vec::new() };
    for row in input.split('\n') {
        let mut f = row.split('\t');
        match f.next() {
            Some("ctx") => {
                for kv in f {
                    let Some((k, v)) = kv.split_once('=') else { continue };
                    match k {
                        "zone" => q.zone = v,
                        "service" => q.service = v,
                        "weight_g" => q.weight_g = num(v),
                        "volumetric_g" => q.volumetric_g = num(v),
                        "cod" => q.cod = num(v),
                        "month" => q.month = num(v),
                        "day" => q.day = num(v),
                        "monthly_orders" => q.monthly_orders = num(v),
                        "return_rate_bp" => q.return_rate_bp = num(v),
                        "floor" => q.floor = num(v),
                        _ => {}
                    }
                }
            }
            Some("line") => {
                let code = f.next().unwrap_or("");
                let amount = num(f.next().unwrap_or("0"));
                let locked = f.next() == Some("1");
                let source = f.next().unwrap_or("");
                let label = f.next().unwrap_or("");
                q.lines.push(Line { code, amount, locked, source, label });
            }
            _ => {}
        }
    }
    q
}

/// Encodes operations for the host.
pub fn encode(ops: &[Op]) -> String {
    let mut out = String::new();
    for op in ops {
        match op {
            Op::Scale { code, permille, reason } => { row(&mut out, &["scale", code]); int(&mut out, *permille); field(&mut out, reason); }
            Op::Set { code, amount, reason } => { row(&mut out, &["set", code]); int(&mut out, *amount); field(&mut out, reason); }
            Op::Remove { code, reason } => { row(&mut out, &["remove", code]); field(&mut out, reason); }
            Op::Add { code, label, amount, reason } => {
                row(&mut out, &["add", code]); int(&mut out, *amount); field(&mut out, label); field(&mut out, reason);
            }
        }
        out.push('\n');
    }
    out
}

fn row(out: &mut String, parts: &[&str]) {
    for (i, p) in parts.iter().enumerate() {
        if i > 0 { out.push('\t'); }
        out.push_str(p);
    }
}

fn field(out: &mut String, s: &str) {
    out.push('\t');
    out.push_str(s);
}

fn int(out: &mut String, v: i64) {
    out.push('\t');
    if v < 0 { out.push('-'); }
    let mut digits = [0u8; 20];
    let mut n = v.unsigned_abs();
    let mut i = digits.len();
    loop {
        i -= 1;
        digits[i] = b'0' + (n % 10) as u8;
        n /= 10;
        if n == 0 { break; }
    }
    for d in &digits[i..] { out.push(*d as char); }
}

fn num(s: &str) -> i64 {
    let (neg, digits) = s.strip_prefix('-').map_or((false, s), |d| (true, d));
    let mut v: i64 = 0;
    for b in digits.bytes() {
        if !b.is_ascii_digit() { break; }
        v = v.saturating_mul(10).saturating_add((b - b'0') as i64);
    }
    if neg { -v } else { v }
}

/// Bump allocator: the host instantiates the module afresh for every call, so nothing is ever freed.
pub mod heap {
    use core::alloc::{GlobalAlloc, Layout};
    use core::cell::UnsafeCell;

    pub struct Bump { next: UnsafeCell<usize> }
    unsafe impl Sync for Bump {}
    impl Bump { pub const fn new() -> Bump { Bump { next: UnsafeCell::new(0) } } }

    unsafe impl GlobalAlloc for Bump {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            let next = unsafe { &mut *self.next.get() };
            if *next == 0 { *next = core::arch::wasm32::memory_size(0) * 65_536; }
            let start = (*next + layout.align() - 1) & !(layout.align() - 1);
            let end = start + layout.size();
            let have = core::arch::wasm32::memory_size(0) * 65_536;
            if end > have {
                let pages = (end - have).div_ceil(65_536);
                if core::arch::wasm32::memory_grow(0, pages) == usize::MAX { return core::ptr::null_mut(); }
            }
            *next = end;
            start as *mut u8
        }
        unsafe fn dealloc(&self, _: *mut u8, _: Layout) {}
    }
}

/// Exports `alloc` and `adjust` for a plugin function `fn(&Quote) -> Vec<Op>`, plus the allocator and panic handler.
#[macro_export]
macro_rules! export_adjust {
    ($f:ident) => {
        #[global_allocator]
        static HEAP: $crate::heap::Bump = $crate::heap::Bump::new();

        #[unsafe(export_name = "alloc")]
        pub extern "C" fn __astra_alloc(len: i64) -> i64 {
            let mut buf: $crate::vec::Vec<u8> = $crate::vec::Vec::with_capacity(len as usize);
            let ptr = buf.as_mut_ptr();
            core::mem::forget(buf);
            ptr as i64
        }

        #[unsafe(export_name = "adjust")]
        pub extern "C" fn __astra_adjust(ptr: i64, len: i64) -> i64 {
            let input = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };
            let text = core::str::from_utf8(input).unwrap_or("");
            let out = $crate::encode(&$f(&$crate::parse(text)));
            let bytes = out.into_bytes();
            let (p, n) = (bytes.as_ptr() as i64, bytes.len() as i64);
            core::mem::forget(bytes);
            (p << 32) | n
        }

        #[panic_handler]
        fn panic(_: &core::panic::PanicInfo) -> ! { core::arch::wasm32::unreachable() }
    };
}
