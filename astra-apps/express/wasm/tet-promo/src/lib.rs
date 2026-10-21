//! Tết promotion of Saigon Express for B2B shippers: 20 % off the base tariff outside the city,
//! free same-city delivery up to 2 kg, Express at +30 % instead of +50 %, and a 0.8 % COD fee.
#![no_std]

const ZONE_BASE: [i64; 4] = [18_000, 30_000, 45_000, 60_000]; // first kg, per zone
const ZONE_STEP: [i64; 4] = [2_500, 5_000, 7_000, 9_000]; // each further 500 g

#[unsafe(no_mangle)]
pub extern "C" fn fee(weight_g: i64, zone: i64, express: i64, cod: i64) -> i64 {
    let z = zone.clamp(0, 3) as usize;
    if z == 0 && weight_g <= 2_000 && express == 0 {
        return cod_fee(cod);
    }
    let extra_steps = ((weight_g - 1_000).max(0) + 499) / 500;
    let mut fee = ZONE_BASE[z] + extra_steps * ZONE_STEP[z];
    if z > 0 {
        fee = fee * 80 / 100;
    }
    if express != 0 {
        fee = fee * 130 / 100;
    }
    round_up_thousand(fee + cod_fee(cod))
}

fn cod_fee(cod: i64) -> i64 {
    if cod <= 0 { 0 } else { (cod * 8 / 1_000).max(4_000) }
}

fn round_up_thousand(v: i64) -> i64 {
    (v + 999) / 1_000 * 1_000
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
