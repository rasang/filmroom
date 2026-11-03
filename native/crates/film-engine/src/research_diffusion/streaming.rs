//! Bounded resident GPU FFT with chunked uploads/readbacks and file-backed outputs.
use super::{full_stage::*, gpu::Runner};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
    sync::{atomic::Ordering, mpsc},
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;
type R<T> = Result<T, String>;
fn wait(r: &Runner, c: &mut impl FnMut() -> bool) -> R<()> {
    let (tx, rx) = mpsc::channel();
    r.queue.on_submitted_work_done(move || {
        let _ = tx.send(());
    });
    let at = Instant::now();
    loop {
        check(c)?;
        if r.error.load(Ordering::Relaxed) {
            return fail("GPU_DEVICE_FAILED");
        };
        let _ = r.device.poll(wgpu::Maintain::Poll);
        if rx.try_recv().is_ok() {
            return Ok(());
        }
        if at.elapsed() > Duration::from_secs(5) {
            return fail("GPU_STAGE_TIMEOUT");
        };
        std::thread::sleep(Duration::from_millis(1));
    }
}
struct Buffers {
    a: wgpu::Buffer,
    b: wgpu::Buffer,
    k: wgpu::Buffer,
    data: wgpu::Buffer,
    readback: wgpu::Buffer,
}
impl Buffers {
    fn scratch(&self, i: u32) -> &wgpu::Buffer {
        if i == 0 { &self.a } else { &self.b }
    }
}
fn dispatch(
    r: &Runner,
    b: &Buffers,
    e: &mut wgpu::CommandEncoder,
    p: [u32; 16],
    count: usize,
    total: &mut u64,
) {
    let u = r
        .device
        .create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("optical pass"),
            contents: bytemuck::cast_slice(&p),
            usage: wgpu::BufferUsages::UNIFORM,
        });
    let list = [&b.a, &b.b, &b.k, &b.data, &u];
    let entries = list
        .iter()
        .enumerate()
        .map(|(i, v)| wgpu::BindGroupEntry {
            binding: i as u32,
            resource: v.as_entire_binding(),
        })
        .collect::<Vec<_>>();
    let group = r.device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("optical bindings"),
        layout: &r.pipeline.get_bind_group_layout(0),
        entries: &entries,
    });
    let mut pass = e.begin_compute_pass(&wgpu::ComputePassDescriptor {
        label: Some("optical FFT"),
        timestamp_writes: None,
    });
    pass.set_pipeline(&r.pipeline);
    pass.set_bind_group(0, &group, &[]);
    let (gx, gy) = dispatch_grid(count);
    pass.dispatch_workgroups(gx, gy, 1);
    *total += 1;
}
fn fft(
    r: &Runner,
    b: &Buffers,
    e: &mut wgpu::CommandEncoder,
    p: &mut [u32; 16],
    current: &mut u32,
    inverse: bool,
    total: &mut u64,
) {
    let n = p[0] as usize;
    p[9] = u32::from(inverse);
    for _ in 0..2 {
        p[7] = 2;
        p[10] = *current;
        p[11] = 1 - *current;
        dispatch(r, b, e, *p, n * n, total);
        *current = 1 - *current;
        let mut span = 2;
        while span <= n {
            p[7] = 3;
            p[8] = span as u32;
            p[10] = *current;
            p[11] = 1 - *current;
            dispatch(r, b, e, *p, n * n, total);
            *current = 1 - *current;
            span *= 2;
        }
        p[7] = 4;
        p[10] = *current;
        p[11] = 1 - *current;
        dispatch(r, b, e, *p, n * n, total);
        *current = 1 - *current;
    }
}
fn upload(
    r: &Runner,
    buffer: &wgpu::Buffer,
    offset: u64,
    data: &[u8],
    cancel: &mut impl FnMut() -> bool,
    bytes: &mut u64,
) -> R<()> {
    check(cancel)?;
    r.queue.write_buffer(buffer, offset, data);
    r.queue.submit([]);
    wait(r, cancel)?;
    *bytes += data.len() as u64;
    Ok(())
}
fn upload_kernel(
    r: &Runner,
    b: &Buffers,
    path: &Path,
    expected: &str,
    n: usize,
    ks: usize,
    c: &mut impl FnMut() -> bool,
    total: &mut u64,
) -> R<()> {
    let mut f = io(File::open(path))?;
    let mut hash = Sha256::new();
    let mut raw = vec![0u8; 65536];
    let mut chunk = Vec::with_capacity(CHUNK_BYTES);
    let mut left = ks * ks * 8;
    let mut offset = 0;
    while left > 0 {
        check(c)?;
        let len = left.min(raw.len());
        io(f.read_exact(&mut raw[..len]))?;
        hash.update(&raw[..len]);
        for v in raw[..len].chunks_exact(8) {
            let x = f64::from_le_bytes(v.try_into().unwrap());
            let admitted = numeric_scalar(x, 1.)?;
            chunk.extend_from_slice(&admitted.to_le_bytes());
        }
        left -= len;
        if chunk.len() == CHUNK_BYTES || left == 0 {
            upload(r, &b.data, offset, &chunk, c, total)?;
            offset += chunk.len() as u64;
            chunk.clear();
        }
    }
    if hex(&hash.finalize()) != expected {
        return fail("GPU_STAGE_KERNEL_HASH");
    };
    let mut tw = Vec::with_capacity(n * 8);
    for i in 0..n {
        let a = -2. * std::f64::consts::PI * i as f64 / n as f64;
        tw.extend_from_slice(&numeric_scalar(a.cos(), 1.)?.to_le_bytes());
        tw.extend_from_slice(&numeric_scalar(a.sin(), 1.)?.to_le_bytes());
    }
    upload(r, &b.data, (ks * ks * 4) as u64, &tw, c, total)
}
fn upload_tile(
    r: &Runner,
    b: &Buffers,
    source: &mut File,
    m: &Manifest,
    n: usize,
    ox: u32,
    oy: u32,
    c: &mut impl FnMut() -> bool,
    total: &mut u64,
) -> R<()> {
    let rows = (CHUNK_BYTES / (n * 8)).max(1);
    let mut chunk = vec![0u8; rows * n * 8];
    let mut source_row = vec![0u8; m.width as usize * 8];
    for first in (0..n).step_by(rows) {
        check(c)?;
        let count = rows.min(n - first);
        read_reflected_chunk(
            source,
            m.width,
            m.height,
            m.radius,
            n,
            ox,
            oy,
            first,
            count,
            &mut source_row,
            &mut chunk[..count * n * 8],
            c,
        )?;
        upload(
            r,
            &b.a,
            (first * n * 8) as u64,
            &chunk[..count * n * 8],
            c,
            total,
        )?;
    }
    Ok(())
}
fn read_tile(
    r: &Runner,
    b: &Buffers,
    current: u32,
    source: &mut File,
    out: &mut File,
    m: &Manifest,
    ox: u32,
    oy: u32,
    ww: usize,
    hh: usize,
    c: &mut impl FnMut() -> bool,
    total: &mut u64,
    uploads: &mut u64,
    dispatches: &mut u64,
) -> R<()> {
    let rows = (CHUNK_BYTES / (ww * 8)).max(1);
    let scatter = m.scatter()?;
    let mut original = vec![0u8; ww * 8];
    let mut output = vec![0u8; ww * 8];
    for first in (0..hh).step_by(rows) {
        check(c)?;
        let count = rows.min(hh - first);
        let bytes = (count * ww * 8) as u64;
        let mut originals=Vec::<f32>::with_capacity(count*ww);
        for dy in 0..count {
            check(c)?;
            let at=((oy as usize+first+dy)*m.width as usize+ox as usize) as u64*8;
            io(source.seek(SeekFrom::Start(at)))?;io(source.read_exact(&mut original))?;
            for bytes in original.chunks_exact(8) {
                let v=f64::from_le_bytes(bytes.try_into().unwrap());
                originals.push(numeric_scalar(v, FFT_SOURCE_BOUND)?);
            }
        }
        let ks=2*m.radius as usize+1;let n=m.fft_side as usize;
        upload(r,&b.data,(ks*ks*4+n*8) as u64,bytemuck::cast_slice(&originals),c,uploads)?;
        drop(originals);
        let mut e = r.device.create_command_encoder(&Default::default());
        let mut params=[0u32;16];params[0]=m.fft_side;params[7]=10;params[8]=(first*ww) as u32;
        params[10]=current;params[11]=1-current;params[12]=ww as u32;params[13]=count as u32;
        params[14]=(ks*ks) as u32;params[15]=scatter.to_bits();
        dispatch(r,b,&mut e,params,count*ww,dispatches);
        e.copy_buffer_to_buffer(
            b.scratch(1-current),
            (first * ww * 8) as u64,
            &b.readback,
            0,
            bytes,
        );
        r.queue.submit([e.finish()]);
        let (tx, rx) = mpsc::channel();
        b.readback
            .slice(0..bytes)
            .map_async(wgpu::MapMode::Read, move |v| {
                let _ = tx.send(v);
            });
        let at = Instant::now();
        loop {
            check(c)?;
            if r.error.load(Ordering::Relaxed) {
                return fail("GPU_DEVICE_FAILED");
            };
            let _ = r.device.poll(wgpu::Maintain::Poll);
            match rx.try_recv() {
                Ok(Ok(())) => break,
                Ok(Err(_)) => return fail("GPU_STAGE_READBACK_FAILED"),
                Err(mpsc::TryRecvError::Disconnected) => return fail("GPU_STAGE_READBACK_FAILED"),
                Err(mpsc::TryRecvError::Empty) => {}
            }
            if at.elapsed() > Duration::from_secs(5) {
                return fail("GPU_STAGE_TIMEOUT");
            };
            std::thread::sleep(Duration::from_millis(1));
        }
        {
            let mapped = b.readback.slice(0..bytes).get_mapped_range();
            let values = bytemuck::cast_slice::<u8, f32>(&mapped);
            for dy in 0..count {
                check(c)?;
                let at = ((oy as usize + first + dy) * m.width as usize + ox as usize) as u64 * 8;
                for x in 0..ww {
                    let value=values[(dy*ww+x)*2];
                    if !value.is_finite(){return fail("NONFINITE_STAGE_OUTPUT");}
                    output[x*8..x*8+8].copy_from_slice(&(value as f64).to_le_bytes());
                }
                io(out.seek(SeekFrom::Start(at)))?;
                io(out.write_all(&output))?;
            }
        }
        b.readback.unmap();
        *total += bytes;
    }
    Ok(())
}
pub(super) fn run(
    dir: &Path,
    m: &Manifest,
    p: &StagePlan,
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let start = Instant::now();
    check(c)?;
    let n = p.fft_side as usize;
    numeric_fft_domain(n, m.scatter_f64()?)?;
    let ks = 2 * p.radius as usize + 1;
    let core = p.core as usize;
    let r = Runner::create(
        (n * n * 8) as u64,
        [
            m.width as u64,
            m.height as u64,
            m.radius as u64,
            n as u64,
            core as u64,
            0,
            0,
            p.device_bytes,
        ],
        false,
    )
    .map_err(|_| "GPU_UNAVAILABLE_OR_LIMITS")?;
    check(c)?;
    let mk = |label, size, usage| {
        r.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size,
            usage,
            mapped_at_creation: false,
        })
    };
    let usage =
        wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST;
    let b = Buffers {
        a: mk("optical A", (n * n * 8) as u64, usage),
        b: mk("optical B", (n * n * 8) as u64, usage),
        k: mk(
            "optical spectrum",
            (n * n * 8) as u64,
            wgpu::BufferUsages::STORAGE,
        ),
        data: mk(
            "optical PSF",
            (ks * ks * 4 + n * 8 + CHUNK_BYTES) as u64,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        ),
        readback: mk(
            "optical bounded readback",
            (CHUNK_BYTES as u64).min(core as u64 * core as u64 * 8),
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        ),
    };
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = OwnedDir(candidate.clone(), false);
    let mut hashes = std::array::from_fn(|_| String::new());
    let mut upload_bytes = 0;
    let mut readback_bytes = 0;
    let mut dispatches = 0;
    let mut sample_count = 0u64;
    let mut sample_max_abs = 0f64;
    let mut sample_sum_squared = 0f64;
    for ch in 0..3 {
        check(c)?;
        upload_kernel(
            &r,
            &b,
            &dir.join(format!("kernel-{ch}.f64le")),
            &m.kernel_sha256[ch],
            n,
            ks,
            c,
            &mut upload_bytes,
        )?;
        let mut params = [0u32; 16];
        params[0] = n as u32;
        params[3] = ks as u32;
        params[4] = m.radius;
        params[14] = (ks * ks) as u32;
        let mut e = r.device.create_command_encoder(&Default::default());
        params[7] = 9;
        params[11] = 0;
        dispatch(&r, &b, &mut e, params, n * n, &mut dispatches);
        let mut current = 0;
        fft(
            &r,
            &b,
            &mut e,
            &mut params,
            &mut current,
            false,
            &mut dispatches,
        );
        params[7] = 5;
        params[10] = current;
        dispatch(&r, &b, &mut e, params, n * n, &mut dispatches);
        r.queue.submit([e.finish()]);
        wait(&r, c)?;
        let mut source = io(File::open(dir.join(format!("source-{ch}.f64le"))))?;
        let output_path = candidate.join(format!("plane-{ch}.f64le"));
        let mut output = io(OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&output_path))?;
        io(output.set_len(m.width as u64 * m.height as u64 * 8))?;
        for oy in (0..m.height as usize).step_by(core) {
            for ox in (0..m.width as usize).step_by(core) {
                check(c)?;
                let ww = core.min(m.width as usize - ox);
                let hh = core.min(m.height as usize - oy);
                upload_tile(
                    &r,
                    &b,
                    &mut source,
                    m,
                    n,
                    ox as u32,
                    oy as u32,
                    c,
                    &mut upload_bytes,
                )?;
                let mut e = r.device.create_command_encoder(&Default::default());
                current = 0;
                fft(
                    &r,
                    &b,
                    &mut e,
                    &mut params,
                    &mut current,
                    false,
                    &mut dispatches,
                );
                params[7] = 6;
                params[10] = current;
                params[11] = 1 - current;
                dispatch(&r, &b, &mut e, params, n * n, &mut dispatches);
                current = 1 - current;
                fft(
                    &r,
                    &b,
                    &mut e,
                    &mut params,
                    &mut current,
                    true,
                    &mut dispatches,
                );
                params[7] = 8;
                params[10] = current;
                params[11] = 1 - current;
                params[12] = ww as u32;
                params[13] = hh as u32;
                dispatch(&r, &b, &mut e, params, ww * hh, &mut dispatches);
                current = 1 - current;
                r.queue.submit([e.finish()]);
                wait(&r, c)?;
                read_tile(
                    &r,
                    &b,
                    current,
                    &mut source,
                    &mut output,
                    m,
                    ox as u32,
                    oy as u32,
                    ww,
                    hh,
                    c,
                    &mut readback_bytes,
                    &mut upload_bytes,
                    &mut dispatches,
                )?;
                let sx = ox as u32 + (ww / 2) as u32;
                let sy = oy as u32 + (hh / 2) as u32;
                let expected = direct_sample(
                    &dir.join(format!("source-{ch}.f64le")),
                    &dir.join(format!("kernel-{ch}.f64le")),
                    m.width,
                    m.height,
                    m.radius,
                    sx,
                    sy,
                    m.scatter_f64()?,
                    c,
                )?;
                let mut scalar = [0u8; 8];
                io(output.seek(SeekFrom::Start(
                    (sy as u64 * m.width as u64 + sx as u64) * 8,
                )))?;
                io(output.read_exact(&mut scalar))?;
                let actual = f64::from_le_bytes(scalar);
                let error = (actual - expected).abs();
                if !error.is_finite() || error > 2e-5 * expected.abs().max(1.) {
                    return fail("GPU_STAGE_NUMERIC_CHECK");
                };
                sample_count += 1;
                sample_max_abs = sample_max_abs.max(error);
                sample_sum_squared += error * error;
            }
        }
        io(output.sync_all())?;
        hashes[ch] = hash_plane(&output_path, m.width as u64 * m.height as u64, c)?;
    }
    check(c)?;
    if r.error.load(Ordering::Relaxed) {
        return fail("GPU_DEVICE_FAILED");
    };
    publish_complete(&candidate, m, p, hashes, c)?;
    guard.1 = true;
    Ok(
        serde_json::json!({"ok":true,"schemaVersion":1,"operation":m.operation,"token":m.token,"manifestSha256":p.manifest_sha256,"gpuUsed":true,"fullImage":true,"width":m.width,"height":m.height,"channels":3,"tileCount":p.tile_count,"elapsedMs":start.elapsed().as_secs_f64()*1000.,"backend":"Vulkan f32 FFT and scatter","adapter":r.adapter,"vendor":r.vendor,"shaderF64Advertised":r.f64_cap,"uploadBytes":upload_bytes+dispatches*64,"readbackBytes":readback_bytes,"dispatches":dispatches,"deviceBytes":p.device_bytes,"sampleCount":sample_count,"sampleMaxAbs":sample_max_abs,"sampleToleranceRelative":2e-5,"sampleToleranceAbsolute":2e-5,"sampleRms":(sample_sum_squared/sample_count.max(1)as f64).sqrt()}),
    )
}
