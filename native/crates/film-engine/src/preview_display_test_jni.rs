//! Bounded instrumentation bridge. Its Kotlin declaration lives only in androidTest.
//! This symbol is also present in release native libraries, deliberately: it has
//! no engine, filesystem, parameter, or routing access, only bounded conversion.
use crate::{
    jni_bridge,
    mobile::{self, EngineError, Result},
    preview_display,
};
use jni::{
    JNIEnv,
    objects::{JByteBuffer, JObject},
    sys::{jint, jstring},
};
use std::panic::{AssertUnwindSafe, catch_unwind};

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativePreviewDisplayTestBridge_pack(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    input: JByteBuffer<'_>,
    output: JByteBuffer<'_>,
    width: jint,
    height: jint,
    cancel_at_check: jint,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        let required = mobile::required_bytes(width, height)?;
        if cancel_at_check < 0 || cancel_at_check as i64 > height as i64 * 2 + 2 {
            return Err(EngineError::new(
                "INVALID_PARAMS",
                "Invalid test cancellation checkpoint",
            ));
        }
        let out_bytes = required / 3;
        let ip = jni_bridge::buffer(&mut env, &input, required, false)?;
        let op = jni_bridge::buffer(&mut env, &output, out_bytes, true)?;
        if !jni_bridge::non_overlapping_ranges(ip as usize, required, op as usize, out_bytes) {
            return Err(EngineError::new(
                "INVALID_BUFFER",
                "Input and output memory ranges overlap",
            ));
        }
        // Both references remain live, native-order/aligned, capacity-checked and
        // disjoint. As with render JNI, callers must not mutate aliases during call.
        let pixels = unsafe { std::slice::from_raw_parts(ip as *const f32, required / 4) };
        let bytes = unsafe { std::slice::from_raw_parts_mut(op, out_bytes) };
        let mut checks = 0;
        preview_display::pack_rgba8(pixels, bytes, width as usize, || {
            checks += 1;
            if cancel_at_check != 0 && checks == cancel_at_check {
                Err(EngineError::new(
                    "CANCELLED",
                    "Synthetic display cancellation",
                ))
            } else {
                Ok(())
            }
        })
    }));
    let value = match result {
        Ok(Ok(())) => serde_json::json!({"ok":true,"outputValid":true}),
        Ok(Err(error)) => serde_json::json!({"ok":false,"outputValid":false,"code":error.code}),
        Err(_) => serde_json::json!({"ok":false,"outputValid":false,"code":"ENGINE_PANIC"}),
    };
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
