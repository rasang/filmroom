# Original glare stream and integer predicate bound

The production source is `rand` 0.9 `StdRng` (ChaCha12), seeded with 42 using the original `SeedableRng::seed_from_u64` key expansion, then `rand_distr` 0.5.1 `LogNormal<f64>` / `StandardNormal` ZIGNOR. No PRNG, distribution, seed, image-coordinate reseeding, or sampling order is substituted.

## Exact fast branch

A ZIGNOR draw uses `m = bits >> 12`, `u = m / 2^51 - 1` and table index `bits & 255`. The integer numerator `abs(m - 2^51)` has at most 52 bits. For each original X-table pair, setup binary-searches the first integer numerator for which the original rounded f64 expression `abs(u * X[i]) < X[i+1]` fails. The shader performs that same monotone decision with an exact 64-bit integer comparison. Original table constants and all integer inputs are unchanged. Setup is 256 bounded calibration searches, never per-pixel CPU evaluation.

## Slow branch, Q48

Let epsilon = 2^-48. Unsigned Q48 uses 64 bits as two u32 words. Products use eight base-2^16 limbs, so every multiply/add temporary fits u32 exactly; the 48-bit shift truncates by less than epsilon. Division is integer long division. There are no floating-point comparisons, floating-point FMA assumptions, or builtin exp/log calls in rejection decisions.

Rounded table constants differ by at most epsilon/2. The exact numerator product gives |x_fixed - x_original| < 2 epsilon. Its `x*x/2` argument differs by less than 10 epsilon for |x| <= 4.

For exp(-t), 0 <= t <= 8, set z = t/256. A 12-term alternating Taylor sum is evaluated with Q48 multiplication and small-integer division. z <= 1/32. Each recursively calculated term incurs less than 3 epsilon, summation plus argument error stays below 41 epsilon, and omitted Taylor terms are far below epsilon. Eight squarings amplify this bound by at most 256, with at most 255 epsilon of additional truncation. The exp error stays below 10,751 epsilon; adding interpolation, argument and ordinary f64 rounding allowances gives the conservative bound 12,000 epsilon.

For Open01, the exact input is odd integer n / 2^53. Normalize n to m in [1, 2), retaining its separate binary exponent. Truncation of m is less than epsilon. Compute z = (m-1)/(m+1), then ln(m) = 2 * sum(z^(2k+1)/(2k+1)), through power 63. Since 0 <= z <= 1/3, the omitted tail is below epsilon. Ratio error is below 1.5 epsilon, the power recurrence and divisions sum to less than 200 epsilon, and the exponent times the ln(2) constant adds at most 27 epsilon. Negative ln(Open01) therefore differs by less than 256 epsilon even at the smallest input 2^-53. Multiplication by inverse R followed by squaring in the tail predicate keeps its total comparison error below 4,096 epsilon (the maximum -ln(input)/R is below 10.1).

The common ambiguity guard is 65,536 epsilon = 2^-32, larger than both conservative bounds. If the integer comparison margin is at most this guard, the selected candidate is not used: the entire GPU tail fails and the original full stage may run on CPU. Non-selected candidates cannot affect the stream. The implementation bounds outer rejection to 16 attempts and tail rejection to 32 attempts, and rejects selected iteration overruns rather than inventing a sample.

The elementary-function reference assumes conventional f64 library accuracy; the guard leaves over 50,000 epsilon (1.7e-10 absolute) beyond the fixed-point bound. Host tests compare all original consumed-word counts, long sequences across windows, extreme uniforms, and a dense exp domain. Device tests must also verify integer words and selection against the same original oracle before a full-migration claim.

## Bounded work and scope

GPU ChaCha blocks are generated in parallel for at most 65,536 random u64 words. Candidate normals are evaluated in parallel at each possible word position. A single selector copies at most 16,384 sequentially selected results to the full-resolution glare plane and returns only produced-count/consumed-word-count/error control values to the host. The next window starts at the exact unconsumed global word cursor. No whole-image sequential invocation is used. Final LogNormal exp is f32, consistent with the experimental arithmetic contract, but it does not participate in RNG decisions.

This proves the arithmetic comparison construction under the stated bounds, not mobile performance. No 10-second latency claim or Adreno device verification follows from host tests.
