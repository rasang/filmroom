# Print exposure first in the print group

User-authorized ordering change only. Actual control: `printExposureEv`; label resource: `text_05af46cca01b` (印相曝光); group: `print` (印相).

The existing Control definition is moved verbatim from `extraControls()` to the first position of `controls()` for that group. Both portrait tool-strip order and landscape section order use `controls()+extraControls()`, so they now start with print exposure, followed by paper. ID, min/max (-4/+4), neutral (0), read/write lambdas, print-route activation, icon/help and exact-value flag are unchanged. The separate adjust group's first `filmExposure` (胶片曝光) is untouched. Existing valid selected IDs remain selected; only a missing/default selection follows the original first-item fallback.

Validation: `PrintExposureOrderHostTest` passes on real MainActivity portrait and landscape, asserting first/second order, uniqueness, retained selected paper, unchanged parameter values, unchanged landscape section instance across rebuild, and filmExposure still first in adjust. `ImportDescriptionHostTest` reran concurrently and passes its four-language menu + document-picker MIME contract. Fresh focused Gradle run: BUILD SUCCESSFUL in35s, 2026-11-02 03:19 UTC; 2 tests, zero failures/errors/skips. Log `/workspace/shared/filmroom-import-and-print-order-tests.log`.

Independent static review: no blocking or out-of-scope changes. No algorithm, parameter value, picker behavior, APK/version or publication changed.
