//! f32 arithmetic mirror of the original scanner gamut equations.
use super::Gamut;
const SRGB_RGB_TO_XYZ: [[f32; 3]; 3] = [
    [0.4124, 0.3576, 0.1805],
    [0.2126, 0.7152, 0.0722],
    [0.0193, 0.1192, 0.9505],
];
const SRGB_XYZ_TO_RGB: [[f32; 3]; 3] = [
    [3.2406, -1.5372, -0.4986],
    [-0.9689, 1.8758, 0.0415],
    [0.0557, -0.204, 1.057],
];
const M1_XYZ_TO_LMS: [[f32; 3]; 3] = [
    [0.8189330101, 0.3618667424, -0.1288597137],
    [0.0329845436, 0.9293118715, 0.0361456387],
    [0.0482003018, 0.2643662691, 0.633851707],
];
const M1_LMS_TO_XYZ: [[f32; 3]; 3] = [
    [1.2270138511035211, -0.5577999806518222, 0.2812561489664678],
    [-0.0405801784232806, 1.11225686961683, -0.07167667866560119],
    [-0.0763812845057069, -0.4214819784180127, 1.5861632204407947],
];
const M2_LMS_TO_LAB: [[f32; 3]; 3] = [
    [0.2104542553, 0.793617785, -0.0040720468],
    [1.9779984951, -2.428592205, 0.4505937099],
    [0.0259040371, 0.7827717662, -0.808675766],
];
const M2_LAB_TO_LMS: [[f32; 3]; 3] = [
    [0.9999999984505196, 0.3963377921737678, 0.21580375806075877],
    [
        1.0000000088817607,
        -0.10556134232365633,
        -0.0638541747717059,
    ],
    [
        1.0000000546724108,
        -0.08948418209496574,
        -1.2914855378640917,
    ],
];
const OKLRAB_K1: f32 = 0.206;
const OKLRAB_K2: f32 = 0.03;
const OKLRAB_K3: f32 = (1.0 + OKLRAB_K1) / (1.0 + OKLRAB_K2);
const CAM16_M16: [[f32; 3]; 3] = [
    [0.401288, 0.650173, -0.051461],
    [-0.250268, 1.204414, 0.045854],
    [-0.002079, 0.048952, 0.953127],
];
const CAM16_M16I: [[f32; 3]; 3] = [
    [1.8620678550872327, -1.0112546305316843, 0.14918677544445175],
    [
        0.3875265432361372,
        0.6214474419314753,
        -0.008973985167612516,
    ],
    [
        -0.015841498849333863,
        -0.03412293802851557,
        1.0499644368778496,
    ],
];
const CAM16_D_RGB: [f32; 3] = [1.0228770275436545, 0.9852074782801457, 0.9285450586783286];
const CAM16_F_L: f32 = 0.6839903845696502;
const CAM16_N: f32 = 0.2;
const CAM16_N_BB: f32 = 1.0003040045593807;
const CAM16_N_CB: f32 = 1.0003040045593807;
const CAM16_Z: f32 = 1.9272135954999579;
const CAM16_A_W: f32 = 37.16907530221132;
const CAM16_C: f32 = 0.69;
const CAM16_N_C: f32 = 1.0;
const UCS_C1: f32 = 0.007;
const UCS_C2: f32 = 0.0228;
fn mat_vec(m: &[[f32; 3]; 3], v: [f32; 3]) -> [f32; 3] {
    [
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
    ]
}
fn xyz_to_oklab(xyz: [f32; 3]) -> [f32; 3] {
    let lms = mat_vec(&M1_XYZ_TO_LMS, xyz);
    let lms_ = [lms[0].cbrt(), lms[1].cbrt(), lms[2].cbrt()];
    mat_vec(&M2_LMS_TO_LAB, lms_)
}
fn oklab_to_xyz(lab: [f32; 3]) -> [f32; 3] {
    let lms_ = mat_vec(&M2_LAB_TO_LMS, lab);
    let lms = [lms_[0].powi(3), lms_[1].powi(3), lms_[2].powi(3)];
    mat_vec(&M1_LMS_TO_XYZ, lms)
}
fn oklab_l_to_lr(l: f32) -> f32 {
    let t = OKLRAB_K3 * l - OKLRAB_K1;
    0.5 * (t + (t * t + 4.0 * OKLRAB_K2 * OKLRAB_K3 * l).sqrt())
}
pub(crate) fn reinhard_knee(d: f32, threshold: f32, limit: f32, power: f32) -> f32 {
    if d > threshold {
        let scale = limit - threshold;
        let x = (d - threshold) / scale;
        let y = x / (1.0 + x.powf(power)).powf(1.0 / power);
        threshold + scale * y
    } else {
        d
    }
}
fn atan2_deg(y: f32, x: f32) -> f32 {
    y.atan2(x).to_degrees().rem_euclid(360.0)
}
fn cam16_padc_forward(rgb: [f32; 3]) -> [f32; 3] {
    let mut out = [0.0f32; 3];
    for i in 0..3 {
        let flr = (CAM16_F_L * rgb[i].abs() / 100.0).powf(0.42);
        out[i] = 400.0 * rgb[i].signum() * flr / (27.13 + flr) + 0.1;
    }
    out
}
fn cam16_padc_inverse(rgb: [f32; 3]) -> [f32; 3] {
    let mut out = [0.0f32; 3];
    for i in 0..3 {
        let d = rgb[i] - 0.1;
        let base = (27.13 * d.abs()) / (400.0 - d.abs());
        out[i] = d.signum() * 100.0 / CAM16_F_L * base.powf(1.0 / 0.42);
    }
    out
}
fn xyz_to_cam16ucs(xyz: [f32; 3]) -> [f32; 3] {
    let xyz100 = [xyz[0] * 100.0, xyz[1] * 100.0, xyz[2] * 100.0];
    let rgb = mat_vec(&CAM16_M16, xyz100);
    let rgb_c = [
        rgb[0] * CAM16_D_RGB[0],
        rgb[1] * CAM16_D_RGB[1],
        rgb[2] * CAM16_D_RGB[2],
    ];
    let [ra, ga, ba] = cam16_padc_forward(rgb_c);
    let a = ra - 12.0 * ga / 11.0 + ba / 11.0;
    let b = (ra + ga - 2.0 * ba) / 9.0;
    let h = atan2_deg(b, a);
    let e_t = 0.25 * ((2.0 + h * std::f32::consts::PI / 180.0).cos() + 3.8);
    let a_resp = (2.0 * ra + ga + ba / 20.0 - 0.305) * CAM16_N_BB;
    let jj = 100.0 * (a_resp / CAM16_A_W).powf(CAM16_C * CAM16_Z);
    let denom = ra + ga + 21.0 * ba / 20.0;
    let t = if denom != 0.0 {
        (50000.0 / 13.0) * CAM16_N_C * CAM16_N_CB * (e_t * (a * a + b * b).sqrt()) / denom
    } else {
        0.0
    };
    let cc = t.powf(0.9) * (jj / 100.0).powf(0.5) * (1.64 - 0.29f32.powf(CAM16_N)).powf(0.73);
    let m = cc * CAM16_F_L.powf(0.25);
    let jp = (1.0 + 100.0 * UCS_C1) * jj / (1.0 + UCS_C1 * jj);
    let mp = (1.0 / UCS_C2) * (1.0 + UCS_C2 * m).ln();
    let hr = h * std::f32::consts::PI / 180.0;
    [jp, mp * hr.cos(), mp * hr.sin()]
}
fn cam16ucs_to_xyz(jab: [f32; 3]) -> [f32; 3] {
    let [jp, ap, bp] = jab;
    let mp = (ap * ap + bp * bp).sqrt();
    let h = atan2_deg(bp, ap);
    let jj = jp / ((1.0 + 100.0 * UCS_C1) - UCS_C1 * jp);
    let m = ((UCS_C2 * mp).exp() - 1.0) / UCS_C2;
    let cc = m / CAM16_F_L.powf(0.25);
    let j_prime = jj.max(2.220446049250313e-16f32);
    let t = (cc / ((j_prime / 100.0).sqrt() * (1.64 - 0.29f32.powf(CAM16_N)).powf(0.73)))
        .powf(1.0 / 0.9);
    let e_t = 0.25 * ((2.0 + h * std::f32::consts::PI / 180.0).cos() + 3.8);
    let a_resp = CAM16_A_W * (jj / 100.0).powf(1.0 / (CAM16_C * CAM16_Z));
    let p1 = if t != 0.0 {
        (50000.0 / 13.0) * CAM16_N_C * CAM16_N_CB * e_t / t
    } else {
        0.0
    };
    let p2 = a_resp / CAM16_N_BB + 0.305;
    let p3 = 21.0 / 20.0;
    let (mut a, mut b) = cam16_opponent_inverse(p1, p2, p3, h);
    // Achromatic guard, matching colour's `ab * np.where(t == 0, 0, 1)`:
    // when t == 0 the hue is undefined and the opponent inverse must be zeroed.
    if t == 0.0 {
        a = 0.0;
        b = 0.0;
    }
    let ra = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
    let ga = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
    let ba = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
    let rgb_c = cam16_padc_inverse([ra, ga, ba]);
    let rgb = [
        rgb_c[0] / CAM16_D_RGB[0],
        rgb_c[1] / CAM16_D_RGB[1],
        rgb_c[2] / CAM16_D_RGB[2],
    ];
    let xyz100 = mat_vec(&CAM16_M16I, rgb);
    [xyz100[0] / 100.0, xyz100[1] / 100.0, xyz100[2] / 100.0]
}
fn cam16_opponent_inverse(p1: f32, p2: f32, p3: f32, h: f32) -> (f32, f32) {
    let hr = h * std::f32::consts::PI / 180.0;
    let s = hr.sin();
    let c = hr.cos();
    let nn = p2 * (2.0 + p3) * (460.0 / 1403.0);
    if s.abs() >= c.abs() {
        let p4 = if s != 0.0 { p1 / s } else { 0.0 };
        let b = nn
            / (p4 + (2.0 + p3) * (220.0 / 1403.0) * (c / s) - (27.0 / 1403.0)
                + p3 * (6300.0 / 1403.0));
        (b * (c / s), b)
    } else {
        let p5 = if c != 0.0 { p1 / c } else { 0.0 };
        let a = nn
            / (p5 + (2.0 + p3) * (220.0 / 1403.0)
                - ((27.0 / 1403.0) - p3 * (6300.0 / 1403.0)) * (s / c));
        (a, a * (s / c))
    }
}
fn lookup(g: &Gamut, l: f32, h: f32) -> f32 {
    let l = l.clamp(g.l_min, g.l_max);
    let pi = std::f32::consts::PI;
    let hi = (h + pi) / (2. * pi / g.n_h as f32);
    let hf = hi.floor();
    let hlo = (hf as i32).rem_euclid(g.n_h as i32) as usize;
    let hhi = (hlo + 1) % g.n_h as usize;
    let ht = hi - hf;
    let li = (l - g.l_min) / (g.l_max - g.l_min) * (g.n_l - 1) as f32;
    let llo = (li.floor() as i32).clamp(0, g.n_l as i32 - 2) as usize;
    let lhi = llo + 1;
    let lt = li - llo as f32;
    let t = &g.cmax;
    let nh = g.n_h as usize;
    t[llo * nh + hlo] as f32 * (1. - lt) * (1. - ht)
        + t[llo * nh + hhi] as f32 * (1. - lt) * ht
        + t[lhi * nh + hlo] as f32 * lt * (1. - ht)
        + t[lhi * nh + hhi] as f32 * lt * ht
}
pub(super) fn compress(g: &Gamut, rgb: [f32; 3]) -> [f32; 3] {
    if g.mode == 0 {
        let ach = rgb[0].max(rgb[1]).max(rgb[2]);
        if ach <= 1e-12 {
            return rgb;
        }
        return rgb
            .map(|v| ach * (1. - reinhard_knee((ach - v) / ach, g.knee[0], g.knee[1], g.knee[2])));
    }
    let xyz = mat_vec(&SRGB_RGB_TO_XYZ, rgb);
    let lab = if g.mode == 3 {
        xyz_to_cam16ucs(xyz)
    } else {
        xyz_to_oklab(xyz)
    };
    let mut l = lab[0];
    if let Some([t, lim, p]) = g.lightness {
        l = reinhard_knee(l / g.l_white, t, lim, p) * g.l_white;
    }
    let c = (lab[1] * lab[1] + lab[2] * lab[2]).sqrt();
    let h = lab[2].atan2(lab[1]);
    let lookup_l = if g.mode == 2 { oklab_l_to_lr(l) } else { l };
    let safe = lookup(g, lookup_l, h).max(1e-9);
    let cn = reinhard_knee(c / safe, g.knee[0], g.knee[1], g.knee[2]) * safe;
    let out = [l, cn * h.cos(), cn * h.sin()];
    let xyz = if g.mode == 3 {
        cam16ucs_to_xyz(out)
    } else {
        oklab_to_xyz(out)
    };
    mat_vec(&SRGB_XYZ_TO_RGB, xyz)
}
