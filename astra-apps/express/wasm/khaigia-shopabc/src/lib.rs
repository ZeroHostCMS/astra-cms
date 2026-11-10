//! Khai giá Astra for Shop ABC: the price terms of its contract annex (docs/demo-data/shop-abc-contract-v1.md),
//! written as changes to the rule layer's price. Lines this function does not mention (express, the 11.11 peak
//! surcharge, anything the business adds to the rules later) stay as the rules computed them.
#![no_std]

use astra_pricing_pdk::{export_adjust, vec, Op, Quote};
use astra_pricing_pdk::vec::Vec;

const MINIMUM: i64 = 15_000;                 // §5: at least 15,000 VND per parcel
const BULKY_PER_HALF_KG: i64 = 4_000;        // §4: per 0.5 kg of volumetric weight above the real weight

fn adjust(q: &Quote) -> Vec<Op> {
    let mut ops = vec![];
    let mut base = q.amount("base");

    // §2: volume tiers on the base freight, by the customer's orders in the last 30 days
    let eleven_eleven_week = q.month == 11 && (9..=15).contains(&q.day);
    if cfg!(feature = "v2") && eleven_eleven_week {
        ops.push(Op::scale("base", 850, "Phụ lục v2 §2a: tuần 11.11 giữ bậc giá cao nhất (−15 %)"));
        base = base * 850 / 1000;
    } else if q.monthly_orders > 2_000 {
        ops.push(Op::scale("base", 850, "Phụ lục §2: trên 2.000 đơn/tháng, −15 % cước nền"));
        base = base * 850 / 1000;
    } else if q.monthly_orders > 500 {
        ops.push(Op::scale("base", 920, "Phụ lục §2: 501–2.000 đơn/tháng, −8 % cước nền"));
        base = base * 920 / 1000;
    }

    // §3: no COD fee while returns stay under 5 %
    let mut cod_fee = q.amount("cod_fee");
    if q.has("cod_fee") && q.return_rate_bp < 500 {
        ops.push(Op::remove("cod_fee", "Phụ lục §3: miễn phí COD khi tỉ lệ hoàn dưới 5 %"));
        cod_fee = 0;
    }

    // §4: bulky parcels pay for volumetric weight, capped at +40 % of the real weight
    let mut bulky = 0;
    if q.volumetric_g > q.weight_g {
        let chargeable = q.volumetric_g.min(q.weight_g * 140 / 100);
        bulky = (chargeable - q.weight_g + 499) / 500 * BULKY_PER_HALF_KG;
        if bulky > 0 {
            ops.push(Op::add("bulky", "Phụ phí hàng cồng kềnh", bulky, "Phụ lục §4: cân quy đổi D×R×C/6000, trần +40 %"));
        }
    }

    // §5: minimum per parcel, topped up on the base line
    let total = q.total() - q.amount("base") + base - q.amount("cod_fee") + cod_fee + bulky;
    if total < MINIMUM {
        ops.push(Op::set("base", base + MINIMUM - total, "Phụ lục §5: tối thiểu 15.000 ₫/đơn"));
    }
    ops
}

export_adjust!(adjust);
