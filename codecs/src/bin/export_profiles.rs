//! Reproducibly emit the exact encoder ICC profiles used by Android PNG.
use film_codecs::output_color::OutputColorSpace;
fn main() {
    let destination =
        std::path::PathBuf::from(std::env::args_os().nth(1).expect("destination directory"));
    std::fs::create_dir_all(&destination).unwrap();
    for (space, name) in [
        (OutputColorSpace::Srgb, "srgb.icc"),
        (OutputColorSpace::DisplayP3, "display-p3.icc"),
        (OutputColorSpace::AdobeRgb, "adobe-rgb-1998.icc"),
    ] {
        std::fs::write(destination.join(name), space.icc().unwrap()).unwrap();
    }
}
