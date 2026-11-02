//! Explicit stock catalog. No arbitrary filesystem profile IDs are accepted.
use serde::Deserialize;
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Stock {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub default_paper: String,
    pub default_route: String,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Catalog {
    pub schema_version: u32,
    pub profiles: Vec<Stock>,
}
pub const CATALOG_JSON: &str = include_str!("../../../data/profile_catalog.json");
pub fn catalog() -> Catalog {
    serde_json::from_str(CATALOG_JSON).expect("compile-time catalog is valid")
}
pub const FILMS: &[&str] = &[
    "kodak_doublex",
    "kodak_trix",
    "fujifilm_c200",
    "fujifilm_pro_400h",
    "fujifilm_provia_100f",
    "fujifilm_velvia_100",
    "fujifilm_xtra_400",
    "kodak_ektachrome_100",
    "kodak_ektar_100",
    "kodak_gold_200",
    "kodak_kodachrome_64",
    "kodak_portra_160",
    "kodak_portra_400",
    "kodak_portra_800",
    "kodak_portra_800_push1",
    "kodak_portra_800_push2",
    "kodak_ultramax_400",
    "kodak_verita_200d",
    "kodak_vision3_200t",
    "kodak_vision3_250d",
    "kodak_vision3_500t",
    "kodak_vision3_50d",
];
pub const PAPERS: &[&str] = &[
    "kodak_2302",
    "fujifilm_crystal_archive_typeii",
    "kodak_2383",
    "kodak_2393",
    "kodak_ektacolor_edge",
    "kodak_endura_premier",
    "kodak_portra_endura",
    "kodak_supra_endura",
    "kodak_ultra_endura",
];
pub fn is_reversal(id: &str) -> bool {
    matches!(
        id,
        "kodak_trix"
            | "fujifilm_provia_100f"
            | "fujifilm_velvia_100"
            | "kodak_ektachrome_100"
            | "kodak_kodachrome_64"
    )
}
pub fn valid_route(film: &str, paper: &str, route: &str) -> bool {
    FILMS.contains(&film)
        && PAPERS.contains(&paper)
        && matches!(route, "print" | "direct")
        && (!is_reversal(film) || route == "direct")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn catalog_matches_data_and_allowlists() {
        let c = catalog();
        assert_eq!(c.schema_version, 1);
        assert_eq!(c.profiles.len(), 31);
        assert_eq!(FILMS.len(), 22);
        assert_eq!(PAPERS.len(), 9);
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let mut seen = std::collections::HashSet::new();
        for s in c.profiles {
            assert!(seen.insert(s.id.clone()));
            let p = spektrafilm_core::profile::load_profile_by_name(&root, &s.id).unwrap();
            assert!(FILMS.contains(&s.id.as_str()) || PAPERS.contains(&s.id.as_str()));
            assert_eq!(is_reversal(&s.id), p.is_positive());
            if FILMS.contains(&s.id.as_str()) {
                assert!(valid_route(&s.id, &s.default_paper, &s.default_route));
            }
        }
    }
    #[test]
    fn route_rejects_unbundled_and_positive_print() {
        assert!(!valid_route("../arbitrary", "kodak_portra_endura", "print"));
        assert!(!valid_route(
            "fujifilm_provia_100f",
            "kodak_portra_endura",
            "print"
        ));
        assert!(valid_route("kodak_gold_200", "kodak_2383", "direct"));
    }
}
