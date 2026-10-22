//! Delivery-piece bonus of a courier for one payroll period (demo tariff of Saigon Express):
//! pieces are paid by tier — 1,500 VND each up to 600, 2,000 VND from 601 to 1,000, 2,500 VND beyond —
//! and a courier who lost no working day earns a 500,000 VND attendance bonus.
#![no_std]

const TIERS: [(i64, i64); 3] = [(600, 1_500), (1_000, 2_000), (i64::MAX, 2_500)]; // (up to pieces, VND per piece)
const FULL_ATTENDANCE_BONUS: i64 = 500_000;

#[unsafe(no_mangle)]
pub extern "C" fn kpi(pieces: i64, payment_days_x10: i64, working_days: i64) -> i64 {
    let pieces = pieces.max(0);
    let mut bonus = 0;
    let mut floor = 0;
    for (ceiling, rate) in TIERS {
        if pieces <= floor {
            break;
        }
        bonus += (pieces.min(ceiling) - floor) * rate;
        floor = ceiling;
    }
    if working_days > 0 && payment_days_x10 >= working_days * 10 {
        bonus += FULL_ATTENDANCE_BONUS;
    }
    bonus
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
