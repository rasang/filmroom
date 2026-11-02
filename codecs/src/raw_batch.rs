//! Per-operation RAW development batch preference. Never a compression-tile or worker-count knob.
use serde::{Deserialize, Serialize};
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum RawBatch {
    Small,
    Standard,
    Large,
    #[default]
    Auto,
}
impl<'de> Deserialize<'de> for RawBatch {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self,D::Error> {
        let id=String::deserialize(deserializer)?;
        Ok(match id.as_str() {"small"=>Self::Small,"standard"=>Self::Standard,"large"=>Self::Large,_=>Self::Auto})
    }
}
impl RawBatch {
    /// Caller computes capacity with its unchanged scratch/base/halo accounting.
    /// An inadmissible classic plan stays inadmissible; preference never repairs or hides it.
    fn choose(self, classic: usize, capacity: usize, automatic_growth: bool, hard_cap: usize) -> usize {
        if capacity < classic { return 0; }
        if classic < 8 { return classic; }
        let grow = self == Self::Large || (self == Self::Auto && automatic_growth);
        let requested = if grow { classic.saturating_mul(2).min(hard_cap) }
            else if self == Self::Small { (classic / 2).max(8) } else { classic };
        let selected = requested.min(capacity).min(hard_cap);
        // Even enlarged stripes keep common CFA phases aligned; don't shrink an odd classic plan.
        if selected > classic { (selected & !1).max(classic) } else { selected }
    }
    pub(crate) fn dng_rows(self, classic: usize, capacity: usize) -> usize {
        self.choose(classic, capacity, true, 256)
    }
    pub(crate) fn camera_rows(self, classic: usize, capacity: usize, hard_cap: usize) -> usize {
        self.choose(classic, capacity, false, hard_cap)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn format_defaults_and_safe_clamping() {
        assert_eq!(RawBatch::Auto.dng_rows(128,228),228);
        assert_eq!(RawBatch::Auto.dng_rows(128,227),226);
        assert_eq!(RawBatch::Auto.dng_rows(73,73),73);
        assert_eq!(RawBatch::Large.dng_rows(73,74),74);
        assert_eq!(RawBatch::Small.dng_rows(8,8),8);
        assert_eq!(RawBatch::Large.dng_rows(128,64),0);
        assert_eq!(RawBatch::Auto.camera_rows(80,156,160),80);
        assert_eq!(RawBatch::Large.camera_rows(80,156,160),156);
        assert_eq!(RawBatch::Standard.dng_rows(128,256),128);
    }
    #[test] fn all_preferences_preserve_admission_bounds() {
        for capacity in 0..600 { for classic in [0,7,8,17,64,73,80,127,128] {
            if classic>capacity {continue;}
            for mode in [RawBatch::Auto,RawBatch::Small,RawBatch::Standard,RawBatch::Large] {
                let n=mode.dng_rows(classic,capacity);
                assert!(n<=capacity && n<=256);
                if classic>=8 {assert!(n>=8);}
                if mode==RawBatch::Standard {assert_eq!(n,classic);}
            }
        }}
    }
    #[test] fn old_and_new_budget_json_and_exports_stay_compatible() {
        use crate::{develop::Budget,export::ExportParams};
        let old=r#"{"memoryBudgetMiB":128,"diskBudgetBytes":1073741824}"#;
        assert_eq!(serde_json::from_str::<Budget>(old).unwrap().raw_batch,RawBatch::Auto);
        for (id,want) in [("auto",RawBatch::Auto),("small",RawBatch::Small),("standard",RawBatch::Standard),("large",RawBatch::Large),("future-value",RawBatch::Auto)] {
            let text=format!(r#"{{"memoryBudgetMiB":128,"diskBudgetBytes":1073741824,"rawBatch":"{id}"}}"#);
            assert_eq!(serde_json::from_str::<Budget>(&text).unwrap().raw_batch,want);
        }
        for invalid in ["null","123","true","{}","[]",r#"{"large":null}"#,r#"{"auto":null}"#] {
            let text=format!(r#"{{"memoryBudgetMiB":128,"diskBudgetBytes":1073741824,"rawBatch":{invalid}}}"#);
            assert!(serde_json::from_str::<Budget>(&text).is_err());
        }
        assert!(serde_json::from_str::<Budget>(r#"{"memoryBudgetMiB":128,"diskBudgetBytes":1,"unknown":2}"#).is_err());
        for format in ["JPEG","TIFF16"] {
            let text=format!(r#"{{"memoryBudgetMiB":128,"diskBudgetBytes":1073741824,"width":32,"height":24,"format":"{format}","quality":95}}"#);
            assert!(serde_json::from_str::<ExportParams>(&text).is_ok());
        }
    }
}
