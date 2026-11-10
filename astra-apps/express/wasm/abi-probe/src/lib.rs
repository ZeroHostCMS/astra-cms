//! Test fixture for the sandbox's bytes ABI (`alloc` + `function(ptr, len) -> i64`): one well-behaved export and
//! three that misbehave, so the host's bounds checks and deadline are tested against a real module; plus an
//! anomaly `check` that misbehaves on one input only.
#![no_std]

static mut BUF: [u8; 1024] = [0; 1024];

#[unsafe(no_mangle)]
pub extern "C" fn alloc(_len: i64) -> i64 {
    core::ptr::addr_of_mut!(BUF) as i64
}

/// Answers its input unchanged.
#[unsafe(no_mangle)]
pub extern "C" fn echo(ptr: i64, len: i64) -> i64 {
    (ptr << 32) | len
}

/// Points far outside its memory.
#[unsafe(no_mangle)]
pub extern "C" fn out_of_bounds(_ptr: i64, _len: i64) -> i64 {
    (0x7fff_0000_i64 << 32) | 16
}

/// Claims a 1 MiB answer.
#[unsafe(no_mangle)]
pub extern "C" fn too_long(ptr: i64, _len: i64) -> i64 {
    (ptr << 32) | (1 << 20)
}

/// Never returns.
#[unsafe(no_mangle)]
pub extern "C" fn spin(ptr: i64, len: i64) -> i64 {
    let mut x = ptr;
    loop {
        x = core::hint::black_box(x.wrapping_mul(31).wrapping_add(len));
    }
}

/// An anomaly detector (`ops.anomaly`) that passes its self-test but never returns for a hub scan measuring
/// exactly 7,777 g: a module that misbehaves only in production, to show the deadline protects the scan.
#[unsafe(no_mangle)]
pub extern "C" fn check(kind: i64, _a: i64, b: i64, _c: i64, _d: i64) -> i64 {
    if kind == 1 && b == 7_777 {
        return spin(b, kind);
    }
    0
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
