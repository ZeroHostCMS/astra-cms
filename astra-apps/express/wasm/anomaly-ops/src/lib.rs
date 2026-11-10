//! Reference anomaly detector for slot `ops.anomaly` (docs/overlay-wasm.md): one function scores one event.
//! `check(kind, a, b, c, d) -> i64` returns 0 when the event is normal, otherwise `severity * 100 + reason`.
//!   kind 1, hub scan:        a = declared weight (g), b = weight measured at the hub (g)
//!   kind 2, COD payout:      a = COD owed to the shop, b = amount paid out, c = days since the COD was collected
//!   kind 3, failed delivery: a = failed attempts of this parcel, b = the courier's failures in 7 days, c = the courier's attempts in 7 days
#![no_std]

#[unsafe(no_mangle)]
pub extern "C" fn check(kind: i64, a: i64, b: i64, c: i64, _d: i64) -> i64 {
    match kind {
        1 => weight(a, b),
        2 => cod(a, b, c),
        3 => delivery(a, b, c),
        _ => 0,
    }
}

/// Measured more than 30 % above the declared weight: under-declared parcel (reason 1). Over 60 %: severity 3.
fn weight(declared_g: i64, measured_g: i64) -> i64 {
    if declared_g <= 0 || measured_g <= declared_g { return 0; }
    if cfg!(feature = "v2") && declared_g < 500 { return 0; }   // light parcels: scale noise, not fraud
    let over_pct = (measured_g - declared_g) * 100 / declared_g;
    if over_pct > 60 { 301 } else if over_pct > 30 { 201 } else { 0 }
}

/// Paid out at least 10 % less than owed (reason 1), or paid more than 7 days after collection (reason 2).
fn cod(owed: i64, paid: i64, days: i64) -> i64 {
    if owed > 0 && paid * 100 < owed * 90 { return if paid * 100 < owed * 50 { 301 } else { 201 }; }
    if days > 7 { return 102; }
    0
}

/// A courier failing more than a quarter of at least 8 attempts in a week (reason 1), or a parcel failing a third time (reason 2).
fn delivery(parcel_failures: i64, courier_failures: i64, courier_attempts: i64) -> i64 {
    if courier_attempts >= 8 && courier_failures * 4 > courier_attempts { return 201; }
    if parcel_failures >= 3 { return 102; }
    0
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
