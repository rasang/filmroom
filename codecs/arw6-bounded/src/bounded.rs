// SPDX-License-Identifier: LGPL-2.1
// Derived from Copyright2026 Nikolay Amiantov <ab@fmap.me>.
// Modified2026 for FilmRoom bounded row I/O; preserve signed integer transform order.
//! Bounded row reconstruction, preserving signed-shift order from pinned donor.
use crate::{
    Result,
    container::{self, Region, TileRecord},
    reference::{
        BitPump,
        geometry::{RegionGeometry, TileCoords, derive_geom},
        pixelops::delinearize,
    },
    storage::{AnonymousFile, Plane, Scratch},
};
use std::io::Cursor;
fn narrow(v: i64) -> Result<i32> {
    Ok(i32::try_from(v).map_err(|_| "ARW6 coefficient overflow")?)
}
fn clamp(i: isize, n: usize) -> usize {
    debug_assert!(n > 0);
    i.clamp(0, n as isize - 1) as usize
}
fn coarse(low: &Plane, high: &Plane, n: usize, flip: bool) -> Result<Vec<i32>> {
    let c = flip as isize;
    let lo = low.row(n)?;
    let a = high.row(clamp(n as isize - 1 + c, high.h))?;
    let b = high.row(clamp(n as isize + c, high.h))?;
    lo.iter()
        .zip(a.iter().zip(&b))
        .map(|(&l, (&a, &b))| narrow(l as i64 - ((a as i64 + b as i64 + 2) >> 2)))
        .collect()
}
fn vertical_row(low: &Plane, high: &Plane, r: usize, flip: bool) -> Result<Vec<i32>> {
    if low.w != high.w || low.h.abs_diff(high.h) > 1 || r >= low.h + high.h {
        return Err("IDWT geometry".into());
    }
    let c = flip as usize;
    if r % 2 == c {
        let n = r / 2;
        if n >= low.h {
            return Err("IDWT coarse extent".into());
        }
        return coarse(low, high, n, flip);
    }
    let n = r / 2;
    if n >= high.h {
        return Err("IDWT detail extent".into());
    }
    let h = high.row(n)?;
    let a = coarse(low, high, clamp(n as isize - c as isize, low.h), flip)?;
    let b = coarse(low, high, clamp(n as isize + 1 - c as isize, low.h), flip)?;
    h.iter()
        .zip(a.iter().zip(&b))
        .map(|(&h, (&a, &b))| narrow(h as i64 + ((a as i64 + b as i64) >> 1)))
        .collect()
}
fn horizontal(low: &[i32], high: &[i32], w: usize) -> Result<Vec<i32>> {
    let mut out = vec![0; 2 * w];
    for n in 0..w {
        out[2 * n] = narrow(
            low[n] as i64 - ((high[clamp(n as isize - 1, w)] as i64 + high[n] as i64 + 2) >> 2),
        )?;
    }
    for n in 0..w {
        out[2 * n + 1] = narrow(
            high[n] as i64 + ((out[2 * n] as i64 + out[2 * clamp(n as isize + 1, w)] as i64) >> 1),
        )?;
    }
    Ok(out)
}
pub fn idwt2d(
    s: &Scratch,
    ll: &Plane,
    lh: &Plane,
    hl: &Plane,
    hh: &Plane,
    flip: bool,
) -> Result<Plane> {
    if ll.w != lh.w || ll.w != hl.w || ll.w != hh.w || ll.h != hl.h || lh.h != hh.h {
        return Err("inconsistent IDWT bands".into());
    }
    let h = (ll.h + lh.h).min(hl.h + hh.h);
    let w = ll.w.min(hl.w);
    let out = Plane::new(s, 2 * w, h)?;
    for r in 0..h {
        s.check()?;
        let l = vertical_row(ll, lh, r, flip)?;
        let right = vertical_row(hl, hh, r, flip)?;
        out.put(r, &horizontal(&l, &right, w)?)?;
    }
    Ok(out)
}
fn diamond_low(lo: &Plane, hi: &Plane, r: usize, h: usize, w: usize) -> Result<Vec<i32>> {
    let l = lo.row(r)?;
    let a = hi.row(r)?;
    let b = hi.row(clamp(r as isize - 1, h))?;
    (0..w)
        .map(|i| {
            let j = clamp(i as isize + 1, w);
            narrow(
                (2 * l[i] as i64 - ((a[i] as i64 + a[j] as i64 + b[i] as i64 + b[j] as i64) >> 2))
                    >> 1,
            )
        })
        .collect()
}
pub fn diamond_row(lo: &Plane, hi: &Plane, r: usize, h: usize, w: usize) -> Result<Vec<i32>> {
    if h == 0 || w == 0 || h > lo.h || h > hi.h || w > lo.w || w > hi.w || r >= h {
        return Err("diamond geometry".into());
    }
    let a = diamond_low(lo, hi, r, h, w)?;
    let b = diamond_low(lo, hi, clamp(r as isize + 1, h), h, w)?;
    let high = hi.row(r)?;
    let mut out = vec![0; 2 * w];
    for i in 0..w {
        let j = clamp(i as isize - 1, w);
        out[2 * i] = narrow(
            ((b[j] as i64 + b[i] as i64 + a[j] as i64 + a[i] as i64) >> 2) + high[i] as i64,
        )?;
        out[2 * i + 1] = a[i];
    }
    Ok(out)
}
fn dequant(v: i32, q: u8) -> Result<i32> {
    if v == 0 || q == 0 {
        return Ok(v);
    }
    if q > 31 {
        return Err("ARW6 quantization shift".into());
    }
    let n = (v as i64).abs();
    Ok(
        i32::try_from(
            (v as i128).signum() * ((((n * 2 + 1) as i128) << (q - 1)) - (n & 1) as i128),
        )
        .map_err(|_| "dequant overflow")?,
    )
}
fn decode_region(
    s: &Scratch,
    regions: &[Region<'_>],
    geoms: &[RegionGeometry],
    ri: usize,
    ci: usize,
) -> Result<Vec<Plane>> {
    let region = &regions[ri];
    let g = &geoms[ri];
    if region.components.len() != g.n_components {
        return Err("component count".into());
    }
    let comp = region.components.get(ci).ok_or("component index")?;
    if comp.orient_count != g.orientations.len() {
        return Err("orientation count".into());
    }
    let planes = g
        .orientations
        .iter()
        .map(|span| {
            Plane::new(
                s,
                g.width,
                span.bottom.checked_sub(span.top).ok_or("span bounds")?,
            )
        })
        .collect::<Result<Vec<_>>>()?;
    let mut work = vec![0i32; g.width.div_ceil(4) * 4];
    let mut offset = 0usize;
    let mut covered: Vec<usize> = g.orientations.iter().map(|span| span.top).collect();
    for (chunk_i, chunk) in comp.chunks.iter().enumerate() {
        s.check()?;
        let end = offset.checked_add(chunk.length).ok_or("chunk overflow")?;
        let bytes = comp.data.get(offset..end).ok_or("chunk range")?;
        offset = end;
        let mut reader = BitPump::endian(Cursor::new(bytes), bitstream_io::BigEndian);
        let window = chunk_i
            .checked_mul(g.rows_per_chunk)
            .ok_or("chunk geometry")?;
        for (oi, (span, plane)) in g.orientations.iter().zip(&planes).enumerate() {
            let start = span.row_base.checked_add(window).ok_or("row overflow")?;
            let end = start.checked_add(g.rows_per_chunk).ok_or("row overflow")?;
            let first = span.top.max(start);
            let last = span.bottom.min(end);
            if first < last {
                if first != covered[oi] {
                    return Err("ARW6 chunk row coverage gap/overlap".into());
                }
                covered[oi] = last;
            }
            for row in first..last {
                if chunk.length == 0 {
                    continue;
                } // explicit zero chunk, still counted as covered
                s.check()?;
                crate::entropy::decode_line(&mut reader, &mut work)?;
                let values = work[..g.width]
                    .iter()
                    .map(|&v| dequant(v, chunk.q[oi]))
                    .collect::<Result<Vec<_>>>()?;
                plane.put(row - span.top, &values)?;
            }
        }
    }
    if covered
        .iter()
        .zip(&g.orientations)
        .any(|(end, span)| *end != span.bottom)
    {
        return Err("ARW6 missing chunk rows".into());
    }
    Ok(planes)
}
fn single(
    s: &Scratch,
    rs: &[Region<'_>],
    gs: &[RegionGeometry],
    r: usize,
    c: usize,
) -> Result<Plane> {
    let mut p = decode_region(s, rs, gs, r, c)?;
    if p.len() != 1 {
        return Err("expected one plane".into());
    }
    Ok(p.remove(0))
}
fn component(
    s: &Scratch,
    rs: &[Region<'_>],
    gs: &[RegionGeometry],
    levels: usize,
    c: usize,
) -> Result<Plane> {
    let mut recon = single(s, rs, gs, 0, c)?;
    for r in 0..recon.h {
        let mut row = recon.row(r)?;
        let mut acc = row[0] as i64;
        for v in row.iter_mut().skip(1) {
            acc += *v as i64;
            *v = narrow(acc)?;
        }
        recon.put(r, &row)?;
    }
    for ri in 1..levels {
        let p = decode_region(s, rs, gs, ri, c)?;
        if p.len() != 3 {
            return Err("expected three detail planes".into());
        }
        recon = idwt2d(s, &recon, &p[1], &p[0], &p[2], gs[ri].vflip)?;
    }
    Ok(recon)
}
fn assemble(
    s: &Scratch,
    planes: &[Plane; 4],
    rec: &TileRecord<'_>,
    out: &AnonymousFile,
    width: usize,
) -> Result<()> {
    let [lo, cr, cb, hi] = planes;
    let h = lo.h.min(hi.h);
    let w = lo.w;
    if hi.w < w || cr.h < h || cr.w < w || cb.h < h || cb.w < w || 2 * w != rec.w || 2 * h != rec.h
    {
        return Err("mosaic coverage geometry".into());
    }
    for r in 0..h {
        s.check()?;
        let green = diamond_row(lo, hi, r, h, w)?;
        let red = cr.row(r)?;
        let blue = cb.row(r)?;
        let mut even = vec![0u16; 2 * w];
        let mut odd = vec![0u16; 2 * w];
        for i in 0..w {
            let g0 = (green[2 * i] as i64 + 2048).clamp(0, 4095) as u16;
            let g1 = (green[2 * i + 1] as i64 + 2048).clamp(0, 4095) as u16;
            let mean = (g0 as i64 + g1 as i64) >> 1;
            odd[2 * i] = delinearize(g0);
            even[2 * i + 1] = delinearize(g1);
            even[2 * i] = delinearize((mean + 2 * red[i] as i64).clamp(0, 4095) as u16);
            odd[2 * i + 1] = delinearize((mean + 2 * blue[i] as i64).clamp(0, 4095) as u16);
        }
        for (dy, row) in [(0, even), (1, odd)] {
            let bytes: Vec<_> = row.iter().flat_map(|v| v.to_le_bytes()).collect();
            let offset = ((rec.y + 2 * r + dy) * width + rec.x) * 2;
            out.write(offset as u64, &bytes)?;
        }
    }
    Ok(())
}
// Exact allocation graph for coefficient files; excludes the separately held mosaic.
fn tile_peak(rec: &TileRecord<'_>) -> Result<u64> {
    let regions = container::parse_tile(rec)?;
    let levels = regions.len().checked_sub(1).ok_or("region geometry")?;
    let geoms = derive_geom(&TileCoords::new(rec.w, rec.h, levels)?);
    let bytes = |w: usize, h: usize| -> Result<u64> {
        w.checked_mul(h)
            .and_then(|v| v.checked_mul(4))
            .map(|v| v as u64)
            .ok_or_else(|| "plane size overflow".into())
    };
    let height = |ri: usize, oi: usize| -> Result<usize> {
        geoms[ri].orientations[oi]
            .bottom
            .checked_sub(geoms[ri].orientations[oi].top)
            .filter(|v| *v > 0)
            .ok_or_else(|| "empty plane".into())
    };
    let mut completed = 0u64;
    let mut peak = 0u64;
    for _ in 0..3 {
        let mut w = geoms[0].width;
        let mut h = height(0, 0)?;
        let mut live = bytes(w, h)?;
        peak = peak.max(completed + live);
        for ri in 1..levels {
            let g = &geoms[ri];
            let hl = height(ri, 0)?;
            let lh = height(ri, 1)?;
            let hh = height(ri, 2)?;
            if w != g.width || h != hl || lh != hh {
                return Err("IDWT planned geometry".into());
            }
            let details = bytes(g.width, hl + lh + hh)?;
            w *= 2;
            h += lh;
            let next = bytes(w, h)?;
            peak = peak.max(completed + live + details + next);
            live = next;
        }
        if w * 2 != rec.w || h * 2 != rec.h {
            return Err("planned mosaic coverage".into());
        }
        completed += live;
    }
    peak = peak.max(completed + bytes(geoms[levels].width, height(levels, 0)?)?);
    Ok(peak)
}

/// Returns anonymous u16 mosaic storage. All coefficient files have dropped on return.
pub const MAX_MEMORY_BYTES:u64=256*1024*1024;
fn validate_memory(bytes_len:usize,w:usize,memory_limit:u64)->Result<()> {
    if !(32*1024*1024..=MAX_MEMORY_BYTES).contains(&memory_limit) ||
       (bytes_len as u64).checked_add(32*1024*1024).and_then(|v|v.checked_add(w as u64*256)).is_none_or(|v|v>memory_limit) {
        return Err("ARW6 memory budget".into());
    }
    Ok(())
}
pub fn plan(bytes:&[u8],w:usize,h:usize)->Result<u64> {plan_with_memory_limit(bytes,w,h,128*1024*1024)}
pub fn plan_with_memory_limit(bytes:&[u8],w:usize,h:usize,memory_limit:u64)->Result<u64> {
    if w==0 || h==0 || w>32768 || h>32768 || w.checked_mul(h).is_none_or(|n|n>52_000_000) {return Err("image bounds".into());}
    validate_memory(bytes.len(),w,memory_limit)?;
    // Validate record table before donor parser: explicit32-bit-safe offsets/counts.
    if bytes.len() < 8 {
        return Err("strip header".into());
    }
    let count = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
    let table = 8usize
        .checked_add(count.checked_mul(24).ok_or("record count overflow")?)
        .filter(|v| *v <= bytes.len())
        .ok_or("record table bounds")?;
    if count == 0 || count > 4096 {
        return Err("record count limit".into());
    }
    let mut previous = table;
    for i in 0..count {
        let pos = 8 + i * 24;
        let offset = usize::try_from(u64::from_le_bytes(bytes[pos..pos + 8].try_into().unwrap()))?;
        if offset < previous || offset >= bytes.len() {
            return Err("tile offset bounds/order".into());
        }
        previous = offset;
    }
    let records = container::parse_records(bytes)?;
    let mut area = 0usize;
    for (i, r) in records.iter().enumerate() {
        if r.w < 16
            || r.w % 16 != 0
            || r.h < 16
            || r.h % 2 != 0
            || r.x % 2 != 0
            || r.y % 2 != 0
            || r.h == 0
            || r.x.checked_add(r.w).is_none_or(|v| v > w)
            || r.y.checked_add(r.h).is_none_or(|v| v > h)
        {
            return Err("tile bounds".into());
        }
        if records[..i]
            .iter()
            .any(|p| r.x < p.x + p.w && p.x < r.x + r.w && r.y < p.y + p.h && p.y < r.y + r.h)
        {
            return Err("tile overlap".into());
        }
        area = area
            .checked_add(r.w.checked_mul(r.h).ok_or("area overflow")?)
            .ok_or("area overflow")?;
    }
    if area != w * h {
        return Err("incomplete mosaic coverage".into());
    }
    let mosaic_bytes = (w * h * 2) as u64;
    let mut coefficient_peak = 0u64;
    for rec in &records {
        coefficient_peak = coefficient_peak.max(tile_peak(rec)?);
    }
    Ok(mosaic_bytes
        .checked_add(coefficient_peak)
        .ok_or("phase peak overflow")?)
}

pub fn decode(s:&Scratch,bytes:&[u8],w:usize,h:usize)->Result<AnonymousFile> {decode_with_memory_limit(s,bytes,w,h,128*1024*1024)}
pub fn decode_with_memory_limit(s:&Scratch,bytes:&[u8],w:usize,h:usize,memory_limit:u64)->Result<AnonymousFile> {
    s.check()?;
    let disk_peak = plan_with_memory_limit(bytes,w,h,memory_limit)?;
    let records = container::parse_records(bytes)?;
    let mosaic_bytes = (w * h * 2) as u64;
    s.admit_peak(disk_peak)?;
    let output = s.reserve(mosaic_bytes)?;
    for rec in &records {
        let regions = container::parse_tile(rec)?;
        if regions.len() < 2 {
            return Err("region count".into());
        }
        let levels = regions.len() - 1;
        let coords = TileCoords::new(rec.w, rec.h, levels)?;
        let geoms = derive_geom(&coords);
        let planes = [
            component(s, &regions, &geoms, levels, 0)?,
            component(s, &regions, &geoms, levels, 1)?,
            component(s, &regions, &geoms, levels, 2)?,
            single(s, &regions, &geoms, levels, 0)?,
        ];
        assemble(s, &planes, rec, &output, w)?;
    }
    Ok(output)
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::reference::{Plane as Memory, idwt};
    use std::sync::{Arc, atomic::AtomicBool};
    fn ctx() -> Scratch {
        Scratch::new(
            &std::env::temp_dir(),
            8 * 1024 * 1024,
            Arc::new(AtomicBool::new(false)),
        )
        .unwrap()
    }
    fn planes(s: &Scratch, w: usize, h: usize, seed: i32) -> (Memory, Plane) {
        let mut a = Memory::new(w, h);
        for (i, v) in a.data.iter_mut().enumerate() {
            *v = ((i as i32 * 17 + seed * 13) % 101) - 50;
        }
        let b = Plane::new(s, w, h).unwrap();
        for r in 0..h {
            b.put(r, a.row(r)).unwrap();
        }
        (a, b)
    }
    #[test]
    fn row_idwt_is_exact_for_parities_edges_and_negative_odd_values() {
        for w in [1, 2, 7, 16] {
            for h in [1, 2, 5] {
                for flip in [false, true] {
                    for extra in [false, true] {
                        let s = ctx();
                        let low = h + usize::from(extra && !flip);
                        let high = h + usize::from(extra && flip);
                        let (a, aa) = planes(&s, w, low, 1);
                        let (b, bb) = planes(&s, w, high, 2);
                        let (c, cc) = planes(&s, w, low, 3);
                        let (d, dd) = planes(&s, w, high, 4);
                        let reference = idwt::idwt2d(&a, &b, &c, &d, flip).unwrap();
                        let actual = super::idwt2d(&s, &aa, &bb, &cc, &dd, flip).unwrap();
                        assert_eq!((reference.w, reference.h), (actual.w, actual.h));
                        for r in 0..actual.h {
                            assert_eq!(reference.row(r), actual.row(r).unwrap());
                        }
                    }
                }
            }
        }
    }
    #[test]
    fn diamond_rows_are_exact() {
        for w in [1, 2, 7, 16] {
            for h in [1, 2, 7] {
                let s = ctx();
                let (a, aa) = planes(&s, w, h, 1);
                let (b, bb) = planes(&s, w, h, 2);
                let reference = idwt::diamond_idwt(&a, &b, h, w);
                for r in 0..h {
                    assert_eq!(reference.row(r), diamond_row(&aa, &bb, r, h, w).unwrap());
                }
            }
        }
    }
    #[test]
    fn scratch_budget_cancel_and_raii() {
        let dir = std::env::temp_dir();
        let cancel = Arc::new(AtomicBool::new(false));
        let s = Scratch::new(&dir, 128, cancel.clone()).unwrap();
        {
            let p = Plane::new(&s, 4, 4).unwrap();
            assert_eq!(s.usage(), (64, 64));
            assert!(Plane::new(&s, 5, 5).is_err());
            assert_eq!(s.usage().0, 64);
            cancel.store(true, std::sync::atomic::Ordering::Relaxed);
            assert!(p.row(0).is_err());
        }
        assert_eq!(s.usage().0, 0);
    }
    #[test]
    fn absent_chunk_is_not_an_explicit_zero_chunk() {
        use crate::{
            container::{Chunk, Component},
            reference::geometry::LatticeSpan,
        };
        let s = ctx();
        let g = vec![RegionGeometry {
            width: 1,
            rows_per_chunk: 1,
            orientations: vec![LatticeSpan {
                top: 0,
                bottom: 1,
                row_base: 0,
            }],
            n_components: 1,
            vflip: false,
        }];
        let mut r = vec![Region {
            components: vec![Component {
                chunks: vec![],
                orient_count: 1,
                data: &[],
            }],
        }];
        assert!(decode_region(&s, &r, &g, 0, 0).is_err());
        assert_eq!(s.usage().0, 0);
        r[0].components[0].chunks.push(Chunk {
            length: 0,
            q: [0; 3],
        });
        let planes = decode_region(&s, &r, &g, 0, 0).unwrap();
        assert_eq!(planes[0].row(0).unwrap(), vec![0]);
    }
    #[test]
    fn record_overflow_offsets_and_coverage_fail_before_allocation() {
        let s = ctx();
        let mut bytes = vec![0u8; 64];
        bytes[..4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode(&s, &bytes, 16, 16).is_err());
        bytes[..4].copy_from_slice(&1u32.to_le_bytes());
        bytes[8..16].copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(decode(&s, &bytes, 16, 16).is_err());
        bytes[8..16].copy_from_slice(&32u64.to_le_bytes());
        bytes[24..28].copy_from_slice(&16u32.to_le_bytes());
        bytes[28..32].copy_from_slice(&16u32.to_le_bytes());
        assert!(decode(&s, &bytes, 32, 16).is_err());
        assert_eq!(s.usage().0, 0);
    }
    #[test]
    fn arithmetic_overflow_rejects() {
        assert!(dequant(i32::MAX, 31).is_err());
        assert!(horizontal(&[i32::MIN], &[i32::MAX], 1).is_err());
    }
}

#[cfg(test)] mod memory_budget_tests {
 use super::*;
 #[test] fn explicit_256_cap_preserves_128_callers_and_bounds() {
  assert!(validate_memory(100*1024*1024,4096,128*1024*1024).is_err());
  assert!(validate_memory(100*1024*1024,4096,192*1024*1024).is_ok());
  assert!(validate_memory(100*1024*1024,4096,256*1024*1024).is_ok());
  assert!(validate_memory(1,4096,257*1024*1024).is_err());
  assert!(validate_memory(1,4096,u64::MAX).is_err());
  assert!(validate_memory(usize::MAX,4096,MAX_MEMORY_BYTES).is_err());
 }
}
