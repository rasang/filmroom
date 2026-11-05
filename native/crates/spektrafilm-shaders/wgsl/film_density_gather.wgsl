// Exact read-only sampling of normalized DIR-corrected film density, before grain.
struct Params { count: u32, _pad0: u32, _pad1: u32, _pad2: u32 }
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> density: array<f32>;
@group(0) @binding(2) var<storage, read> offsets: array<u32>;
@group(0) @binding(3) var<storage, read_write> samples: array<f32>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let i = id.x;
    if i >= params.count { return; }
    let src = offsets[i] * 3u;
    let dst = i * 3u;
    samples[dst] = density[src];
    samples[dst + 1u] = density[src + 1u];
    samples[dst + 2u] = density[src + 2u];
}
