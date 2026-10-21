//! A pricing script that never returns: what a buggy or hostile tenant script looks like.
//! The Tier 2 sandbox must stop it at its deadline without harming the kernel or other tenants.
#![no_std]

#[unsafe(no_mangle)]
pub extern "C" fn fee(weight_g: i64, _zone: i64, _express: i64, _cod: i64) -> i64 {
    let mut i = weight_g;
    loop {
        i = core::hint::black_box(i.wrapping_add(1));
    }
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
