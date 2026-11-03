# Tail finite-math admission and integer failure transport

WGSL may assume runtime floating values are finite, and out-of-domain or overflowing operations can produce an indeterminate finite result. A NaN sentinel and later `is_finite`-style test are therefore not a proof of rejection. The tail no longer uses that construction.

## Invariant

All file inputs and uploaded constants are first validated as finite f64 and finite after f32 narrowing on the host. Shader guards maintain a conservative absolute scalar bound of 1e15. A bound or domain failure sets a request-wide `atomic<u32>` and returns an inert zero. That zero is never accepted as a replacement pixel: any nonzero flag rejects the entire candidate and leaves the original stage available for CPU fallback.

- Addition/subtraction of admitted operands has magnitude at most 2e15. Multiplication is at most 1e30. These raw operations cannot overflow f32; their result is checked against 1e15 before reuse.
- Division checks both operand bounds and rejects denominator magnitude below 1e-15 before evaluation. Its raw quotient is at most 1e30, then checked against the invariant.
- General power checks a nonnegative base, handles zero only for positive powers, rejects positive bases below 1e-30, and requires `log(base) * power` in [-60,34] before evaluating the original power. The precheck product is itself finite. The actual result is bounded before reuse. Exp uses the same exponent interval. Log requires its input in [1e-30,1e15]. Sqrt requires [0,1e15]. These conservative windows are admission restrictions, not clamps.
- Scanner `pow(10, logXYZ)` has its own pre-operation logXYZ window [-30,15]. Matrix coefficients and operands use guarded scalar products and left-associated sums. Unsharp, brightness and preview conversion retain their original operation order.
- CAM fractional powers reject negative bases. Inverse CAM rejects response magnitude >=400 before dividing by `400-abs(response)`. It skips divisions whose results the original `t==0` branch unconditionally discards. atan2(0,0) is explicitly the original zero angle. Remaining raw CAM intermediates are bounded combinations of response values, fixed coefficients, and already admitted values; their largest temporary products are below 1e19, far below f32 overflow.
- Full-line FIR and both IIR recurrences use the same guarded scalar arithmetic, preserving their original evaluation order. The three exact source expressions must each occur once. Missing or duplicated expressions reject shader construction rather than silently removing guards. Other users of this helper must uphold the finite-input/bounded-output invariant around their own image operations.

The flags do not need to survive floating point arithmetic. A separate four-byte storage buffer holds the integer atomic, initialized to zero for each new Runner and never cleared within that request. It is binding 7 only for the explicit guarded constructor. Existing six-storage-buffer shaders retain their previous inferred layout. The four bytes fit inside the existing 65,536-byte metadata allowance.

The host reads the actual u32 flag after all spatial work, before creating the output candidate, and again after every final output chunk has been processed, before keeping the candidate. Candidate publication therefore cannot rely solely on the eight arithmetic witness pixels or on a final finite scan. Any dummy output produced after an earlier error is discarded. These changes do not claim that the reference's out-of-domain cases are valid GPU results.

## Evidence still required

Source tests check exact guard composition and absence of the old tail/glare finite sentinel. Naga validates the full composed shader. Ignored device tests cover an unsampled logXYZ=50 pixel, sticky flag persistence, fresh-request zero initialization, both six/seven-buffer layouts, and the original RNG stream. Host compilation and Naga do not establish Adreno execution or performance.
