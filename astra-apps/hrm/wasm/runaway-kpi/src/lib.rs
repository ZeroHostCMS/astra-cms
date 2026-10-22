//! A payroll formula that never returns: what a buggy or hostile tenant script looks like.
//! The Tier 2 sandbox must stop it at its deadline, and SalaryComponentHook must refuse to save it.
#![no_std]

#[unsafe(no_mangle)]
pub extern "C" fn kpi(pieces: i64, _payment_days_x10: i64, _working_days: i64) -> i64 {
    let mut i = pieces;
    loop {
        i = core::hint::black_box(i.wrapping_add(1));
    }
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
