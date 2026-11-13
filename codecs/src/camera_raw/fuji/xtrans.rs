//! Conservative full-resolution X-Trans interpolation. Uses the actual 6×6 CFA,
//! preserves measured channels, and interpolates only missing channels from local
//! same-color samples. Global coordinates and a 3-pixel radius make stripes exact.
//! This is basic development, not Fujifilm JPEG rendering or a detail-enhancing algorithm.
use super::*;
const RADIUS:isize=3;
pub(crate) fn develop(d:&Development,y:usize,h:usize,data:Vec<f32>,cancel:&AtomicBool)->Result<rawler::pixarray::Color2D<f32,3>>{
    let rgb=interpolate(&d.cfa,y-d.active.y,d.active.w,h,&data,cancel)?;
    drop(data);
    let mut linear=d.clone();linear.cpp=3;linear.cfa.clear();
    crate::develop::develop_tile_cancel(&linear,y,h,rgb,cancel)
}
pub(crate) fn camera(d:&Development,y:usize,h:usize,data:Vec<f32>,cancel:&AtomicBool)->Result<rawler::pixarray::Color2D<f32,3>>{
    let rgb=interpolate(&d.cfa,y-d.active.y,d.active.w,h,&data,cancel)?;
    drop(data);
    let pixels=rgb.chunks_exact(3).map(|p|[p[0],p[1],p[2]]).collect();
    Ok(rawler::pixarray::Color2D::new_with(pixels,d.active.w,h))
}
fn interpolate(pattern:&str,y_origin:usize,w:usize,h:usize,raw:&[f32],cancel:&AtomicBool)->Result<Vec<f32>>{
    if pattern.len()!=36 || w<8 || h<8 || raw.len()!=w*h{return Err(unsupported());}
    let cfa=pattern.as_bytes();let color=|y:usize,x:usize|match cfa[((y+y_origin)%6)*6+x%6]{b'R'=>0,b'G'=>1,b'B'=>2,_=>3};
    if cfa.iter().any(|v|!matches!(v,b'R'|b'G'|b'B')){return Err(unsupported());}
    // Precompute phase/color neighborhoods. This avoids scanning 49 positions for
    // every missing channel while preserving deterministic accumulation order.
    let mut neighbors=vec![vec![Vec::<(isize,isize,f32)>::new();3];36];
    for py in 0..6 {for px in 0..6 {for dy in -RADIUS..=RADIUS {for dx in -RADIUS..=RADIUS {
        if dx==0 && dy==0 {continue;}
        let cy=(py as isize+dy).rem_euclid(6) as usize;let cx=(px as isize+dx).rem_euclid(6) as usize;
        let ch=match cfa[cy*6+cx]{b'R'=>0,b'G'=>1,_=>2};
        neighbors[py*6+px][ch].push((dx,dy,1./(dx*dx+dy*dy) as f32));
    }}}}
    let mut out=vec![0f32;w*h*3];
    for y in 0..h {
        if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"));}
        for x in 0..w {
            let known=color(y,x);let phase=((y+y_origin)%6)*6+x%6;
            let pixel=&mut out[(y*w+x)*3..(y*w+x+1)*3];pixel[known]=raw[y*w+x];
            for ch in 0..3 {if ch==known{continue;}
                let(mut sum,mut weight)=(0.,0.);
                for &(dx,dy,k) in &neighbors[phase][ch]{
                    let xx=x as isize+dx;let yy=y as isize+dy;
                    if xx>=0 && yy>=0 && xx<w as isize && yy<h as isize {sum+=raw[yy as usize*w+xx as usize]*k;weight+=k;}
                }
                if weight<=0. {return Err(unsupported());}pixel[ch]=sum/weight;
            }
        }
    }Ok(out)
}
#[cfg(test)] mod tests {
    use super::*;
    const CFA:&str="GRGGBGBGBRGRGRGGBGGBGGRGRGRBGBGBGGRG";
    #[test] fn color_constants_and_measured_samples_survive(){
        let c=AtomicBool::new(false);let pattern=rawler::CFA::new(CFA);let(w,h)=(41,43);
        let raw:Vec<_>=(0..h).flat_map(|y|(0..w).map(move|x|(y,x))).map(|(y,x)|[0.2,0.4,0.8][pattern.color_at(y,x)]).collect();
        let rgb=interpolate(CFA,0,w,h,&raw,&c).unwrap();
        for p in rgb.chunks_exact(3){for (a,b) in p.iter().zip([0.2,0.4,0.8]){assert!((*a-b).abs()<2e-6);}}
    }
    #[test] fn stripes_keep_all_six_phases_and_edges(){
        let c=AtomicBool::new(false);let(w,h)=(43,97);let raw:Vec<f32>=(0..w*h).map(|i|((i*719)%1009) as f32/1009.).collect();
        for phase in 0..6 {
            let full=interpolate(CFA,phase,w,h,&raw,&c).unwrap();
            for rows in [8,13,16,31,64]{let mut start=0;while start<h{
                let end=(start+rows).min(h);let top=start.saturating_sub(8);let bottom=(end+8).min(h);
                let part=interpolate(CFA,top+phase,w,bottom-top,&raw[top*w..bottom*w],&c).unwrap();
                assert_eq!(&full[start*w*3..end*w*3],&part[(start-top)*w*3..(end-top)*w*3]);start=end;
            }}
        }
    }
    #[test] fn invalid_pattern_and_cancellation_fail_closed(){
        assert!(interpolate("R",0,8,8,&[0.;64],&AtomicBool::new(false)).is_err());
        assert_eq!(interpolate(CFA,0,8,8,&[0.;64],&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");
    }
}
