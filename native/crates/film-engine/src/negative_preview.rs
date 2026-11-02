//! A stage-4/5 film light-table illustration, NOT a print preview or inverted RGB.
//! Real CMY density -> spectral transmittance under D65 -> CIE XYZ -> sRGB.
//! No auto-exposure, scanner glare, luminance remap, paper, or creative tone curve.
//! Four stratified source samples per thumbnail pixel are averaged in light (XYZ),
//! not log density. This is a bounded spatial approximation, not an exact area scan.
use spektrafilm_core::profile::Profile;
use spektrafilm_math::{colorspace, spectral};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};

pub const MAX_EDGE: u32 = 256;
const MAX_SOURCE_EDGE: u32 = 131_072; // At most three 1 MiB source row buffers.
fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}
fn cancelled(cancel: &mut impl FnMut() -> bool) -> io::Result<()> {
    if cancel() {
        Err(io::Error::new(
            io::ErrorKind::Interrupted,
            "Negative preview cancelled",
        ))
    } else {
        Ok(())
    }
}
struct TempGuard(PathBuf);
impl Drop for TempGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// Accepts a resolved runtime profile (`Pipeline::film`), not an unselected B&W
/// development-time family. Do not resolve again here: the pipeline owns that choice.
/// Returns None for reversal/positive/unknown film types: never fabricate a negative.
/// A non-cancellation preview failure may be ignored by the exporter; checkpoints
/// remain authoritative. Published files are complete PNGs; stale .tmp is removed
/// on retry. Only call with an exclusively owned export-job directory.
pub fn write_negative_preview(
    planes: &[PathBuf; 3],
    profile: &Profile,
    width: u32,
    height: u32,
    job_dir: &Path,
    cancel: &mut impl FnMut() -> bool,
) -> io::Result<Option<PathBuf>> {
    cancelled(cancel)?;
    if profile.info.film_type != "negative" {
        return Ok(None);
    }
    if width == 0 || height == 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE {
        return Err(invalid("Invalid negative preview dimensions"));
    }
    let model = LightTable::new(profile)?;
    let edge = width.max(height);
    let w = ((width as u64 * MAX_EDGE.min(edge) as u64) / edge as u64).max(1) as u32;
    let h = ((height as u64 * MAX_EDGE.min(edge) as u64) / edge as u64).max(1) as u32;
    let mut files = planes
        .iter()
        .map(File::open)
        .collect::<io::Result<Vec<_>>>()?;
    let expected = width as u64 * height as u64 * 8;
    for file in &files {
        if file.metadata()?.len() != expected {
            return Err(invalid("Invalid density plane size"));
        }
    }
    let mut rows = vec![vec![0u8; width as usize * 8]; 3];
    let mut rgb = vec![0u8; w as usize * h as usize * 3];
    for y in 0..h {
        cancelled(cancel)?;
        let mut xyz_row = vec![[0.; 3]; w as usize];
        for sy in 0..2 {
            let iy = sample_coordinate(y, sy, height, h);
            for (file, row) in files.iter_mut().zip(&mut rows) {
                file.seek(SeekFrom::Start(iy as u64 * width as u64 * 8))?;
                file.read_exact(row)?;
            }
            for x in 0..w {
                for sx in 0..2 {
                    let ix = sample_coordinate(x, sx, width, w) as usize;
                    let density = std::array::from_fn(|c| {
                        f64::from_le_bytes(rows[c][ix * 8..ix * 8 + 8].try_into().unwrap())
                    });
                    if density.iter().any(|v| !v.is_finite()) {
                        return Err(invalid("Nonfinite film density"));
                    }
                    let xyz = model.xyz(density)?;
                    for c in 0..3 {
                        xyz_row[x as usize][c] += xyz[c] * 0.25;
                    }
                }
            }
        }
        for x in 0..w as usize {
            let encoded = encode_srgb(xyz_row[x])?;
            let offset = (y as usize * w as usize + x) * 3;
            rgb[offset..offset + 3].copy_from_slice(&encoded);
        }
    }
    cancelled(cancel)?;
    let png = png_rgb(w, h, &rgb);
    let output = job_dir.join("negative-preview.png");
    let temp = job_dir.join("negative-preview.png.tmp");
    match fs::remove_file(&temp) {
        Ok(()) => (),
        Err(e) if e.kind() == io::ErrorKind::NotFound => (),
        Err(e) => return Err(e),
    }
    let _guard = TempGuard(temp.clone());
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)?;
    file.write_all(&png)?;
    file.sync_all()?;
    drop(file);
    cancelled(cancel)?;
    fs::rename(&temp, &output)?;
    File::open(job_dir)?.sync_all()?;
    Ok(Some(output))
}
fn sample_coordinate(pixel: u32, sub: u32, source: u32, target: u32) -> u32 {
    (((pixel as u64 * 4 + sub as u64 * 2 + 1) * source as u64) / (target as u64 * 4))
        .min(source as u64 - 1) as u32
}
struct LightTable {
    dye: Vec<[f64; 3]>,
    base: Vec<f64>,
    weights: Vec<[f64; 3]>,
}
impl LightTable {
    fn new(profile: &Profile) -> io::Result<Self> {
        let n = spectral::N_WAVELENGTHS;
        if profile.data.channel_density.len() != n || profile.data.base_density.len() != n {
            return Err(invalid("Incomplete film spectrum"));
        }
        if !profile.data.wavelengths.is_empty()
            && (profile.data.wavelengths.len() != n
                || profile
                    .data
                    .wavelengths
                    .iter()
                    .enumerate()
                    .any(|(i, v)| (*v - (380. + i as f64 * 5.)).abs() > 1e-6))
        {
            return Err(invalid("Unsupported spectral wavelength grid"));
        }
        let normalization: f64 = (0..n)
            .map(|i| spectral::ILLUMINANT_D65_F64[i] * spectral::CMF_Y_F64[i])
            .sum();
        let mut dye = Vec::new();
        let mut base = Vec::new();
        let mut weights = Vec::new();
        for i in 0..n {
            let row = &profile.data.channel_density[i];
            if row.len() < 3 {
                return Err(invalid("Incomplete dye spectrum"));
            }
            // Same missing-wavelength convention as the physical scanner: absent
            // spectral measurements contribute zero light, never invented data.
            if !profile.data.base_density[i].is_finite() || row[..3].iter().any(|v| !v.is_finite())
            {
                continue;
            }
            dye.push([row[0], row[1], row[2]]);
            base.push(profile.data.base_density[i]);
            let light = spectral::ILLUMINANT_D65_F64[i] / normalization;
            weights.push([
                spectral::CMF_X_F64[i] * light,
                spectral::CMF_Y_F64[i] * light,
                spectral::CMF_Z_F64[i] * light,
            ]);
        }
        if weights.is_empty() {
            return Err(invalid("Missing film spectral measurements"));
        }
        Ok(Self { dye, base, weights })
    }
    fn xyz(&self, density: [f64; 3]) -> io::Result<[f64; 3]> {
        let mut xyz = [0.; 3];
        for i in 0..self.dye.len() {
            let d = self.base[i] + (0..3).map(|c| density[c] * self.dye[i][c]).sum::<f64>();
            let transmittance = 10f64.powf(-d);
            if !transmittance.is_finite() {
                return Err(invalid("Nonfinite spectral transmittance"));
            }
            for c in 0..3 {
                xyz[c] += transmittance * self.weights[i][c];
            }
        }
        Ok(xyz)
    }
}
fn encode_srgb(xyz: [f64; 3]) -> io::Result<[u8; 3]> {
    let mut out = [0; 3];
    for c in 0..3 {
        let v = (0..3)
            .map(|k| colorspace::XYZ_TO_SRGB_F64[c][k] * xyz[k])
            .sum::<f64>();
        if !v.is_finite() {
            return Err(invalid("Nonfinite preview color"));
        }
        // Display clipping, then standard sRGB OETF; no exposure compensation.
        let v = v.clamp(0., 1.);
        let v = if v <= 0.0031308 {
            12.92 * v
        } else {
            1.055 * v.powf(1. / 2.4) - 0.055
        };
        out[c] = (v * 255.).round() as u8;
    }
    Ok(out)
}
// Tiny standards-compliant RGB8 PNG encoder. Stored DEFLATE blocks avoid adding
// an Android dependency for a <=197 KiB thumbnail. CRC32 and Adler32 cover all data.
pub(crate) fn png_rgb(w: u32, h: u32, rgb: &[u8]) -> Vec<u8> {
    let mut raw = Vec::with_capacity(rgb.len() + h as usize);
    for row in rgb.chunks_exact(w as usize * 3) {
        raw.push(0);
        raw.extend_from_slice(row);
    }
    let mut z = vec![0x78, 0x01];
    let count = raw.len().div_ceil(65535);
    for (i, block) in raw.chunks(65535).enumerate() {
        z.push(if i + 1 == count { 1 } else { 0 });
        let len = block.len() as u16;
        z.extend_from_slice(&len.to_le_bytes());
        z.extend_from_slice(&(!len).to_le_bytes());
        z.extend_from_slice(block);
    }
    let (mut a, mut b) = (1u32, 0u32);
    for &v in &raw {
        a = (a + v as u32) % 65521;
        b = (b + a) % 65521;
    }
    z.extend_from_slice(&((b << 16) | a).to_be_bytes());
    let mut png = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut header = Vec::new();
    header.extend_from_slice(&w.to_be_bytes());
    header.extend_from_slice(&h.to_be_bytes());
    header.extend_from_slice(&[8, 2, 0, 0, 0]);
    chunk(&mut png, b"IHDR", &header);
    chunk(&mut png, b"sRGB", &[0]);
    chunk(&mut png, b"IDAT", &z);
    chunk(&mut png, b"IEND", &[]);
    png
}
fn chunk(png: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    png.extend_from_slice(&(data.len() as u32).to_be_bytes());
    png.extend_from_slice(kind);
    png.extend_from_slice(data);
    let mut crc = !0u32;
    for &b in kind.iter().chain(data) {
        crc ^= b as u32;
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xedb88320u32.wrapping_mul(crc & 1));
        }
    }
    png.extend_from_slice(&(!crc).to_be_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    static SEQ: AtomicU64 = AtomicU64::new(0);
    struct Fixture {
        dir: PathBuf,
        planes: [PathBuf; 3],
    }
    impl Fixture {
        fn new(w: u32, h: u32, value: impl Fn(u32, u32) -> f64) -> Self {
            let dir = std::env::temp_dir().join(format!(
                "film-negative-test-{}-{}",
                std::process::id(),
                SEQ.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&dir).unwrap();
            let planes = std::array::from_fn(|i| dir.join(format!("plane-{i}.f64")));
            for path in &planes {
                let mut f = File::create(path).unwrap();
                for y in 0..h {
                    for x in 0..w {
                        f.write_all(&value(x, y).to_le_bytes()).unwrap();
                    }
                }
            }
            Self { dir, planes }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }
    fn profile() -> Profile {
        let mut p = spektrafilm_core::profile::load_profile_by_name(
            &PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data"),
            "kodak_portra_400",
        )
        .unwrap();
        p.data.channel_density = vec![vec![1. / 3.; 3]; spectral::N_WAVELENGTHS];
        p.data.base_density = vec![0.; spectral::N_WAVELENGTHS];
        p
    }
    // Independent stored-DEFLATE decoder for pixel assertions. External Pillow
    // decode additionally checks PNG CRC/zlib interoperability in the QA script.
    fn pixels(bytes: &[u8]) -> (u32, u32, Vec<u8>) {
        assert_eq!(&bytes[..8], b"\x89PNG\r\n\x1a\n");
        let w = u32::from_be_bytes(bytes[16..20].try_into().unwrap());
        let h = u32::from_be_bytes(bytes[20..24].try_into().unwrap());
        let mut offset = 8;
        let mut data = Vec::new();
        while offset < bytes.len() {
            let n = u32::from_be_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
            if &bytes[offset + 4..offset + 8] == b"IDAT" {
                data.extend_from_slice(&bytes[offset + 8..offset + 8 + n]);
            }
            offset += 12 + n;
        }
        let mut at = 2;
        let mut raw = Vec::new();
        loop {
            let final_block = data[at];
            at += 1;
            let n = u16::from_le_bytes(data[at..at + 2].try_into().unwrap()) as usize;
            assert_eq!(
                u16::from_le_bytes(data[at + 2..at + 4].try_into().unwrap()),
                !(n as u16)
            );
            at += 4;
            raw.extend_from_slice(&data[at..at + n]);
            at += n;
            if final_block == 1 {
                break;
            }
        }
        let mut rgb = Vec::new();
        for row in raw.chunks_exact(w as usize * 3 + 1) {
            assert_eq!(row[0], 0);
            rgb.extend_from_slice(&row[1..]);
        }
        (w, h, rgb)
    }
    #[test]
    fn neutral_density_obeys_transmission_not_rgb_inversion() {
        let f = Fixture::new(3, 1, |x, _| x as f64);
        let path = write_negative_preview(&f.planes, &profile(), 3, 1, &f.dir, &mut || false)
            .unwrap()
            .unwrap();
        let (w, h, rgb) = pixels(&fs::read(path).unwrap());
        assert_eq!((w, h), (3, 1));
        for (i, expected) in [255i16, 89, 25].iter().enumerate() {
            for c in 0..3 {
                assert!((rgb[i * 3 + c] as i16 - expected).abs() <= 1, "{rgb:?}");
            }
        }
        // A numeric inversion would give zero for density 1, not ~89.
        assert!(rgb[3] > 80);
    }
    #[test]
    fn bounded_thumbnail_averages_light_not_log_density() {
        let f = Fixture::new(512, 2, |x, _| if x % 2 == 0 { 0. } else { 2. });
        let path = write_negative_preview(&f.planes, &profile(), 512, 2, &f.dir, &mut || false)
            .unwrap()
            .unwrap();
        let (w, h, rgb) = pixels(&fs::read(path).unwrap());
        assert_eq!((w, h), (256, 1));
        // mean(10^0,10^-2)=0.505; sRGB ~188. mean density=1 would be89.
        assert!(rgb.iter().all(|v| (185..=190).contains(v)));
    }
    #[test]
    fn no_upscaling_and_reversal_is_not_fabricated() {
        let f = Fixture::new(1, 2, |_, _| 0.2);
        let mut p = profile();
        p.info.film_type = "positive".into();
        assert!(
            write_negative_preview(&f.planes, &p, 1, 2, &f.dir, &mut || false)
                .unwrap()
                .is_none()
        );
        assert!(!f.dir.join("negative-preview.png").exists());
        p.info.film_type = "negative".into();
        let path = write_negative_preview(&f.planes, &p, 1, 2, &f.dir, &mut || false)
            .unwrap()
            .unwrap();
        let (w, h, _) = pixels(&fs::read(path).unwrap());
        assert_eq!((w, h), (1, 2));
    }
    #[test]
    fn cancel_after_write_cleans_temp_and_preserves_existing_publication() {
        let f = Fixture::new(2, 2, |_, _| 0.3);
        let published = f.dir.join("negative-preview.png");
        fs::write(&published, b"old-valid-publication").unwrap();
        let mut calls = 0;
        let error = write_negative_preview(&f.planes, &profile(), 2, 2, &f.dir, &mut || {
            calls += 1;
            calls == 5
        })
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Interrupted);
        assert_eq!(fs::read(published).unwrap(), b"old-valid-publication");
        assert!(!f.dir.join("negative-preview.png.tmp").exists());
    }
    #[test]
    fn invalid_density_and_dimensions_never_publish() {
        let f = Fixture::new(2, 2, |_, _| f64::NAN);
        assert!(
            write_negative_preview(&f.planes, &profile(), 2, 2, &f.dir, &mut || false).is_err()
        );
        assert!(
            write_negative_preview(&f.planes, &profile(), 0, 2, &f.dir, &mut || false).is_err()
        );
        assert!(!f.dir.join("negative-preview.png").exists());
    }
    #[test]
    fn film_base_and_dye_spectra_change_actual_image() {
        let mut p = profile();
        let clear = LightTable::new(&p).unwrap().xyz([0.; 3]).unwrap();
        p.data.base_density = vec![1.; spectral::N_WAVELENGTHS];
        let fogged = LightTable::new(&p).unwrap().xyz([0.; 3]).unwrap();
        assert!((fogged[1] / clear[1] - 0.1).abs() < 1e-12);
        p.data.base_density.fill(0.);
        p.data
            .channel_density
            .iter_mut()
            .for_each(|v| *v = vec![1., 0., 0.]);
        let light = LightTable::new(&p).unwrap();
        assert!(
            (light.xyz([1., 0., 0.]).unwrap()[1] / light.xyz([0., 1., 0.]).unwrap()[1] - 0.1).abs()
                < 1e-12
        );
    }
    #[test]
    fn actual_portra_transmission_matches_existing_physical_scanner() {
        use spektrafilm_math::{image::ImageBuf, precision::from_f64};
        let p = spektrafilm_core::profile::load_profile_by_name(
            &PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data"),
            "kodak_portra_400",
        )
        .unwrap();
        let density = [0.25, 0.5, 0.75];
        let model = LightTable::new(&p).unwrap();
        let xyz = model.xyz(density).unwrap();
        let image = ImageBuf::from_data(1, 1, density.map(from_f64).to_vec());
        let dyes: Vec<[f64; 3]> = p
            .data
            .channel_density
            .iter()
            .map(|v| [v[0], v[1], v[2]])
            .collect();
        let norm: f64 = (0..spectral::N_WAVELENGTHS)
            .map(|i| spectral::ILLUMINANT_D65_F64[i] * spectral::CMF_Y_F64[i])
            .sum();
        let identity = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        let reference = spektrafilm_gpu::cpu_backend::scan_spectral_cpu(
            &image,
            &dyes,
            &p.data.base_density,
            &spectral::ILLUMINANT_D65_F64,
            norm,
            &identity,
            &identity,
        );
        for c in 0..3 {
            assert!((xyz[c] - reference.data[c] as f64).abs() < 1e-6);
        }
    }
    #[test]
    fn actual_doublex_runtime_families_write_png_and_match_physical_scanner() {
        use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name};
        use spektrafilm_math::{image::ImageBuf, precision::from_f64};
        let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let raw = load_profile_by_name(&data, "kodak_doublex").unwrap();
        let print = load_profile_by_name(&data, "kodak_2302").unwrap();
        assert_eq!(raw.data.development_time, vec![4., 5., 6.5, 9., 12.]);
        let norm: f64 = (0..spectral::N_WAVELENGTHS)
            .map(|i| spectral::ILLUMINANT_D65_F64[i] * spectral::CMF_Y_F64[i]).sum();
        let identity = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        for (index, &time) in raw.data.development_time.iter().enumerate() {
            let mut params = RuntimeParams::default();
            params.film_render.development_time = Some(time);
            let pipe = Pipeline::new(raw.clone(), print.clone(), params);
            let p = &pipe.film;
            assert_eq!(p.data.development_time, vec![time]);
            for i in 0..spectral::N_WAVELENGTHS {
                assert_eq!(p.data.channel_density[i], vec![raw.data.channel_density[i][0], 0., 0.]);
                assert_eq!(p.data.base_density[i], raw.data.base_density_rows[i][index]);
            }
            let model = LightTable::new(p).unwrap();
            let density = [0.5; 3];
            let xyz = model.xyz(density).unwrap();
            let image = ImageBuf::from_data(1, 1, density.map(from_f64).to_vec());
            let dyes: Vec<[f64; 3]> = p.data.channel_density.iter().map(|v| [v[0], v[1], v[2]]).collect();
            let reference = spektrafilm_gpu::cpu_backend::scan_spectral_cpu(
                &image, &dyes, &p.data.base_density, &spectral::ILLUMINANT_D65_F64,
                norm, &identity, &identity,
            );
            for c in 0..3 { assert!((xyz[c] - reference.data[c] as f64).abs() < 1e-6, "time={time} channel={c}"); }
            // Real publication and decoder exercise, not merely a finite matrix.
            let f = Fixture::new(3, 1, |x, _| x as f64 * 0.5);
            let path = write_negative_preview(&f.planes, p, 3, 1, &f.dir, &mut || false).unwrap().unwrap();
            let (w, h, rgb) = pixels(&fs::read(path).unwrap());
            assert_eq!((w, h), (3, 1));
            for x in 0..3 {
                assert_eq!(&rgb[x * 3..x * 3 + 3], &encode_srgb(model.xyz([x as f64 * 0.5; 3]).unwrap()).unwrap());
            }
            assert!(rgb[..3].iter().any(|v| *v > 0));
            for c in 0..3 { assert!(rgb[c] > rgb[3 + c] && rgb[3 + c] > rgb[6 + c]); }
        }
    }
    #[test]
    fn all_shipped_negative_profiles_have_usable_spectra() {
        let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data");
        for name in crate::profile_catalog::FILMS {
            let raw = spektrafilm_core::profile::load_profile_by_name(&data, name).unwrap();
            let p = spektrafilm_core::profile::resolve_for_render(raw, None);
            if p.info.film_type == "negative" {
                let model = LightTable::new(&p).unwrap_or_else(|e| panic!("{name}: {e}"));
                let rgb = encode_srgb(model.xyz([0.5; 3]).unwrap()).unwrap();
                assert!(rgb.iter().any(|v| *v > 0), "{name}");
            }
        }
    }
    #[test]
    fn published_png_fixture_for_external_decoder() {
        // Synthetic nonprivate chart only. Optional env output supports independent
        // decoder QA without retaining files during ordinary test runs.
        if let Ok(dir) = std::env::var("FILM_NEGATIVE_QA_DIR") {
            let f = Fixture::new(512, 256, |x, y| x as f64 / 200. + y as f64 / 500.);
            fs::create_dir_all(&dir).unwrap();
            write_negative_preview(
                &f.planes,
                &profile(),
                512,
                256,
                Path::new(&dir),
                &mut || false,
            )
            .unwrap();
        }
    }
}
