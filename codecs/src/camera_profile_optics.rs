//! Coordinate composition for independently selected embedded/database optics.
//! Every map keeps its own source reference. Continuous boxes and Jacobian
//! bounds certify the combined map before it can reach the RGB sampler.
use super::*;
use crate::lensfun::ProfileSelection;

type Bounds = [f64; 4];

fn shift(mut bounds: Bounds, dx: f64, dy: f64) -> Bounds {
    bounds[0]+=dx; bounds[2]+=dx; bounds[1]+=dy; bounds[3]+=dy;
    bounds
}

fn scaled_bounds(bounds: Bounds, center: [f64;2], minimum: f64, maximum: f64) -> Option<Bounds> {
    if bounds.iter().chain(center.iter()).any(|v|!v.is_finite())
        || !minimum.is_finite() || !maximum.is_finite() || minimum<0.0 || maximum<minimum
        || bounds[0]>bounds[2] || bounds[1]>bounds[3] {return None;}
    let mut output=[0.0;4];
    for axis in 0..2 {
        let lo=bounds[axis]-center[axis];let hi=bounds[axis+2]-center[axis];
        let products=[lo*minimum,lo*maximum,hi*minimum,hi*maximum];
        output[axis]=center[axis]+products.into_iter().fold(f64::INFINITY,f64::min);
        output[axis+2]=center[axis]+products.into_iter().fold(f64::NEG_INFINITY,f64::max);
    }
    Some(output)
}

fn inside(bounds: Bounds, width: usize, height: usize) -> bool {
    bounds.iter().all(|v|v.is_finite()) && bounds[0]>=-1e-7 && bounds[1]>=-1e-7
        && bounds[2]<=(width-1) as f64+1e-7 && bounds[3]<=(height-1) as f64+1e-7
}

fn embedded_tca<'a>(d:&'a Development<'_>)->Option<&'a crate::embedded_optics::TcaModel> {
    match &d.lens_extras.tca {State::Ready(t) if !t.is_identity()=>Some(t),_=>None}
}

fn output_bounds(d:&Development)->Bounds {
    let reference=d.lens_reference;
    [(d.crop.x-reference.x) as f64,(d.crop.y-reference.y) as f64,
        (d.crop.x+d.crop.w-1-reference.x) as f64,(d.crop.y+d.crop.h-1-reference.y) as f64]
}

fn distortion_bounds(d:&Development,p:&ProfileSelection,extra:f64,output:Bounds)->Option<Bounds> {
    let reference=d.lens_reference;
    let center=[reference.w as f64*0.5,reference.h as f64*0.5];
    match &d.lens {
        LensStatus::Ready(model)=>{
            let zoom=model.autofill_zoom()*extra;
            if zoom>64.0 {return None;}
            // For every supported embedded radial map, 0 < D(r) <= its
            // validated autofill bound. This envelope covers every radius,
            // including knots and polynomial extrema, not sampled corners.
            scaled_bounds(output,center,0.0,1.0/extra)
        },
        LensStatus::Missing if p.distortion.is_some()=>{
            let geometry=p.distortion.as_ref()?;let r=p.reference();
            let input=shift(output,reference.x as f64-r.x as f64,reference.y as f64-r.y as f64);
            let mut bounds=geometry.source_bounds(input,extra)?;
            // The constructor separately proves the complete source footprint
            // lies within this rectangle. Intersecting two valid enclosures is
            // safe and removes interval dependency overestimation (a large
            // central radial factor does not also occur at every edge).
            bounds[0]=bounds[0].max(0.0);bounds[1]=bounds[1].max(0.0);
            bounds[2]=bounds[2].min((r.w-1) as f64);bounds[3]=bounds[3].min((r.h-1) as f64);
            if bounds[0]>bounds[2] || bounds[1]>bounds[3] {return None;}
            Some(shift(bounds,r.x as f64-reference.x as f64,r.y as f64-reference.y as f64))
        },
        LensStatus::Missing|LensStatus::Unsupported=>scaled_bounds(output,center,1.0/extra,1.0/extra),
        LensStatus::Profile(_)=>None,
    }
}

fn distortion_jacobian(d:&Development,p:&ProfileSelection,extra:f64)->Option<f64> {
    match &d.lens {
        LensStatus::Ready(model)=>Some(model.sampling_jacobian_bound()?/(model.autofill_zoom()*extra)),
        LensStatus::Missing if p.distortion.is_some()=>Some(p.distortion.as_ref()?.sampling_jacobian_bound()?/extra),
        LensStatus::Missing|LensStatus::Unsupported=>Some(1.0/extra),
        LensStatus::Profile(_)=>None,
    }
}

/// A certified 128x32 tile must fit the existing 8 MiB source read window.
/// Bounds include both intra-channel spatial movement and separation of RGB
/// channels, so admission does not rely on a few sampled pixel coordinates.
fn window_fits(d:&Development,jacobian:f64,separation:f64,radius:[f64;2])->bool {
    if !jacobian.is_finite() || jacobian<=0.0 || !separation.is_finite() || separation<0.0 {return false;}
    let diagonal=((d.crop.w.min(128)-1) as f64).hypot((d.crop.h.min(32)-1) as f64);
    let local=jacobian*diagonal;
    let width=(local+radius[0]*separation).ceil()+5.0;
    let height=(local+radius[1]*separation).ceil()+5.0;
    let bytes=width.min(d.active.w as f64)*height.min(d.active.h as f64)*12.0;
    bytes.is_finite() && bytes<=8.0*1024.0*1024.0
}

fn window_certified(d:&Development,p:&ProfileSelection,extra:f64)->Option<()> {
    let reference=d.lens_reference;
    let before=distortion_bounds(d,p,extra,output_bounds(d))?;
    let jacobian=distortion_jacobian(d,p,extra)?;
    if let Some(tca)=embedded_tca(d) {
        // Manufacturer TCA uses its own unrotated active/crop reference.
        if !inside(before,reference.w,reference.h) {return None;}
        let (minimum,maximum,tca_jacobian)=tca.sampling_bounds(matches!(d.lens,LensStatus::Ready(_)))?;
        let center=[reference.w as f64*0.5,reference.h as f64*0.5];
        let radius=[(before[0]-center[0]).abs().max((before[2]-center[0]).abs()),
            (before[1]-center[1]).abs().max((before[3]-center[1]).abs())];
        window_fits(d,jacobian*tca_jacobian,maximum-minimum,radius).then_some(())
    } else if matches!(d.lens_extras.tca,State::Missing) && p.tca.is_some() {
        let tca=p.tca.as_ref()?;let r=p.reference();
        let local=shift(before,reference.x as f64-r.x as f64,reference.y as f64-r.y as f64);
        let (minimum,maximum,tca_jacobian)=tca.sampling_bounds_for(local)?;
        let center=tca.center_pixels();
        let radius=[(local[0]-center[0]).abs().max((local[2]-center[0]).abs()),
            (local[1]-center[1]).abs().max((local[3]-center[1]).abs())];
        window_fits(d,jacobian*tca_jacobian,maximum-minimum,radius).then_some(())
    } else {
        window_fits(d,jacobian,0.0,[0.0;2]).then_some(())
    }
}

fn composed_box_inside(d:&Development,p:&ProfileSelection,extra:f64,output:Bounds)->Option<()> {
    let reference=d.lens_reference;
    let before=distortion_bounds(d,p,extra,output)?;
    if let Some(tca)=embedded_tca(d) {
        if !inside(before,reference.w,reference.h) {return None;}
        let (minimum,maximum,_)=tca.sampling_bounds(matches!(d.lens,LensStatus::Ready(_)))?;
        let center=[reference.w as f64*0.5,reference.h as f64*0.5];
        inside(scaled_bounds(before,center,minimum,maximum)?,reference.w,reference.h).then_some(())
    } else if matches!(d.lens_extras.tca,State::Missing) && p.tca.is_some() {
        let tca=p.tca.as_ref()?;let r=p.reference();
        let local=shift(before,reference.x as f64-r.x as f64,reference.y as f64-r.y as f64);
        for channel in 0..3 {
            let after=tca.post_source_bounds(local,channel)?;
            let after=shift(after,r.x as f64-reference.x as f64,r.y as f64-reference.y as f64);
            if !inside(after,reference.w,reference.h) {return None;}
        }
        Some(())
    } else {inside(before,reference.w,reference.h).then_some(())}
}

/// Certify a covering of the entire destination rectangle, not isolated edge
/// samples. Subdivision preserves correlations between position and radius;
/// it must be attempted before increasing uniform crop. Full rectangles also
/// avoid assuming a mixed pair of radial maps has a common center or that its
/// post-map is globally invertible merely because each source model is valid.
fn rectangle_certified(d:&Development,p:&ProfileSelection,extra:f64,rectangle:Bounds,
    depth:u8,budget:&mut u32)->bool {
    if *budget==0 {return false;}
    *budget-=1;
    if composed_box_inside(d,p,extra,rectangle).is_some() {return true;}
    if depth==26 {return false;}
    let axis=if rectangle[2]-rectangle[0]>=rectangle[3]-rectangle[1] {0}else{1};
    let middle=(rectangle[axis]+rectangle[axis+2])*0.5;
    if middle<=rectangle[axis] || middle>=rectangle[axis+2] {return false;}
    let mut left=rectangle;let mut right=rectangle;
    left[axis+2]=middle;right[axis]=middle;
    rectangle_certified(d,p,extra,left,depth+1,budget)
        && rectangle_certified(d,p,extra,right,depth+1,budget)
}

fn composition_certified(d:&Development,p:&ProfileSelection,extra:f64)->Option<()> {
    // Memory uses its independent full-domain derivative/separation bound;
    // accepting small boundary boxes cannot hide a large source read window.
    window_certified(d,p,extra)?;
    let mut budget=16384;
    rectangle_certified(d,p,extra,output_bounds(d),0,&mut budget).then_some(())
}

fn choose_zoom(d:&Development,p:&ProfileSelection)->Option<f64> {
    if embedded_tca(d).is_none() && p.tca.is_none() {
        // The external distortion constructor already certifies its complete
        // reference boundary. Do not change its established auto-crop merely
        // because a larger interval box is less tight than that proof.
        let jacobian=p.distortion.as_ref()?.sampling_jacobian_bound()?;
        return window_fits(d,jacobian,0.0,[0.0;2]).then_some(1.0);
    }
    let mut extra=embedded_tca(d).map_or(1.0,|t|t.autofill_zoom());
    if p.tca.is_some() {extra=extra.max(1.001_f32 as f64);}
    for _ in 0..160 {
        if composition_certified(d,p,extra).is_some() {return Some(extra);}
        extra*=1.025;
        if extra>32.0 {break;}
    }
    None
}

pub(super) fn qualify_profile(d:&mut Development) {
    let Some(mut profile)=d.lens_profile.take() else {return;};
    if !matches!(d.lens,LensStatus::Missing) {profile.distortion=None;}
    if !matches!(d.lens_extras.tca,State::Missing) {profile.tca=None;}
    if !matches!(d.lens_extras.vignette,State::Missing) {profile.vignette=None;}
    if profile.distortion.is_some() || profile.tca.is_some() {
        let mut zoom=choose_zoom(d,&profile);
        if zoom.is_none() && profile.tca.is_some() {
            profile.tca=None;
            zoom=choose_zoom(d,&profile);
        }
        if zoom.is_none() {
            // An external operation cannot invalidate an already admitted
            // embedded operation, or prevent ordinary RAW development.
            profile.distortion=None;
            profile.tca=None;
        }
        profile.extra_zoom=zoom.unwrap_or(1.0);
    }
    d.lens_profile=Some(profile);
}

pub(super) fn profile_position(d:&Development,p:&ProfileSelection,x:usize,y:usize,channel:usize)->Result<[f64;2]> {
    let reference=d.lens_reference;let r=p.reference();
    let profile_offset=[r.x as f64-reference.x as f64,r.y as f64-reference.y as f64];
    let extra=p.extra_zoom;
    let mut position=match &d.lens {
        LensStatus::Ready(model)=>model.source_position_with_zoom(reference.w,reference.h,x,y,
            model.autofill_zoom()*extra)?,
        LensStatus::Missing if p.distortion.is_some()=>{
            let px=(reference.x+x).checked_sub(r.x).ok_or_else(||err("INVALID_INPUT","lens_geometry"))?;
            let py=(reference.y+y).checked_sub(r.y).ok_or_else(||err("INVALID_INPUT","lens_geometry"))?;
            let source=p.distortion.as_ref().unwrap().source_position_with_zoom(px,py,extra)?;
            [source[0]+profile_offset[0],source[1]+profile_offset[1]]
        },
        LensStatus::Missing|LensStatus::Unsupported=>{
            let center=[reference.w as f64*0.5,reference.h as f64*0.5];
            [center[0]+(x as f64-center[0])/extra,center[1]+(y as f64-center[1])/extra]
        },
        LensStatus::Profile(_)=>return Err(err("INVALID_INPUT","lens_geometry")),
    };
    if let Some(tca)=embedded_tca(d) {
        position=tca.source_position_after_distortion(reference.w,reference.h,position[0],position[1],channel,
            matches!(d.lens,LensStatus::Ready(_)))?;
    } else if matches!(d.lens_extras.tca,State::Missing) {
        if let Some(tca)=&p.tca {
            position=tca.post_source_position(position[0]-profile_offset[0],position[1]-profile_offset[1],channel)?;
            position[0]+=profile_offset[0];
            position[1]+=profile_offset[1];
        }
    }
    for (value,limit) in position.iter_mut().zip([(reference.w-1) as f64,(reference.h-1) as f64]) {
        if !value.is_finite() || *value < -1e-7 || *value > limit+1e-7 {return Err(err("INVALID_INPUT","lens_geometry"));}
        *value=value.clamp(0.0,limit);
    }
    Ok(position)
}
