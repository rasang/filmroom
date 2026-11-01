use spektrafilm_model::grain::layer_particle_model;
use spektrafilm_math::precision::from_f64;
#[test]
fn coordinate_grain_is_tile_order_independent() {
 let (w,h)=(19u32,13u32);let data:Vec<_>=(0..w*h).map(|i|from_f64(0.2+(i%17) as f64*0.05)).collect();
 let full=layer_particle_model(&data,w,h,2.2,15.0,0.97,42,0.,Some([0,0]));
 let mut tiled=vec![from_f64(0.);data.len()];
 for top in (0..h).step_by(5) {for left in (0..w).step_by(7) {
  let tw=(w-left).min(7);let th=(h-top).min(5);let mut tile=Vec::new();
  for y in top..top+th {for x in left..left+tw {tile.push(data[(y*w+x) as usize]);}}
  let out=layer_particle_model(&tile,tw,th,2.2,15.0,0.97,42,0.,Some([left,top]));
  for y in 0..th {for x in 0..tw {tiled[((top+y)*w+left+x) as usize]=out[(y*tw+x) as usize];}}
 }}
 assert_eq!(full,tiled);
 let changed=layer_particle_model(&data,w,h,2.2,15.0,0.97,43,0.,Some([0,0]));
 assert_ne!(full,changed);
 let repeated=layer_particle_model(&data,w,h,2.2,15.0,0.97,42,0.,Some([0,0]));
 assert_eq!(full,repeated);
}
