//! Independent absence of a recording medium. `none` is not a real profile ID
//! and is never passed to the spectral profile loader. This module deliberately
//! does not reinterpret the historical `direct` (negative scan) route.

pub const NONE: &str = "none";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaSelection {
    FilmAndPaper,
    FilmOnly,
    PaperOnly,
    Neither,
}

impl MediaSelection {
    pub fn from_ids(film: &str, paper: &str) -> Self {
        match (film == NONE, paper == NONE) {
            (false, false) => Self::FilmAndPaper,
            (false, true) => Self::FilmOnly,
            (true, false) => Self::PaperOnly,
            (true, true) => Self::Neither,
        }
    }
    pub fn has_film(self) -> bool {
        matches!(self, Self::FilmAndPaper | Self::FilmOnly)
    }
    pub fn has_paper(self) -> bool {
        matches!(self, Self::FilmAndPaper | Self::PaperOnly)
    }
    /// A new transport is required; existing two-profile execution is not safe.
    pub fn needs_neutral_bridge(self) -> bool { self != Self::FilmAndPaper }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn selectors_are_independent() {
        for (film, paper, expected, has_film, has_paper) in [
            ("kodak_portra_400", "kodak_portra_endura", MediaSelection::FilmAndPaper, true, true),
            ("kodak_portra_400", NONE, MediaSelection::FilmOnly, true, false),
            (NONE, "kodak_portra_endura", MediaSelection::PaperOnly, false, true),
            (NONE, NONE, MediaSelection::Neither, false, false),
        ] {
            let selected = MediaSelection::from_ids(film, paper);
            assert_eq!(selected, expected);
            assert_eq!(selected.has_film(), has_film);
            assert_eq!(selected.has_paper(), has_paper);
            assert_eq!(selected.needs_neutral_bridge(), film == NONE || paper == NONE);
        }
    }
    #[test]
    fn unknown_profiles_are_not_none_or_defaulted() {
        assert_eq!(MediaSelection::from_ids("missing", "missing"), MediaSelection::FilmAndPaper);
        // Validating profile IDs remains the catalog's responsibility, so corrupt
        // presets cannot silently change into an unfiltered image.
    }
}
