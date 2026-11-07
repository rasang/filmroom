Current revision (2026-11-04): New snapshots use the reviewed Portra160/800 v5 arrow shapes and Portra400 v6 brand-letter weight (retaining its v5 arrows), Gold200/Ektar100/E100 v4 lower-rail coordinate models, and three Fuji v2 corrections. See v2/README.txt and tools/medium120/v4 v5 and v6 for per-stock evidence, coverage, methods and limitations. All earlier v1/v2/v4/v5 PNGs and exact-version definitions are preserved for frozen snapshots and reproducible generation. The following report is historical v1 provenance, not the current asset selection.

120 九款片边研究审阅包（未完成实证，2026-11-03）

本包是合成的版式研究，不是实物扫描，不可声称所有数字、位置、节距、字体或颜色均为实测。九条母带和五种裁切仅供核对方向和结构；空缺代表证据不足，不是原片确有空白。645旋转，其他画幅按胶片长边裁切；画幅和相机实际开窗可不同。45项一致性检查仅证明裁切来自同一母带，不证明母带真实。未使用漏光、齿孔或未经授权的外部照片像素。

Portra 160：照片确认41–55与另一侧1–12，末端56未在该照片辨清。位置、周期和箭头仍为估计。来源 https://www.photrio.com/forum/attachments/img_e4614-jpg.283384/
Portra 400：以同一实片可辨的49、50、51及另一侧8、空箭头、9为局部，双侧排列相位和间距为示意，不能视作整卷。用户图1另见54、55与12，非同卷数据不拼作已证实的连续卷。来源 https://www.coastalfilmlab.com/cdn/shop/files/Cut120FilmArchivalAsset.jpg?v=1762191231 及 https://bobgriffithsphoto.uk/?p=6401
Portra 800：对应已核照片的800-2批次，实片同卷41–56、对侧1–12与间隔箭头。周期和相位估计，不能泛化所有生产批次。来源 https://statefilm.com/digital-contact-sheets/sample-sheet.jpg
Gold 200：用户图3只核到52、对侧10及空箭头，连续序列尚缺，示意中未画出的内容不可视作实际空白。
Ektar 100：用户图2可辨49、50、51与对侧7、8；两张图片未确定是否同卷，所以组合和相位仅作示意，不可声称已证实整卷。
E100：用户图4只确认局部型号及8、箭头，整卷尚待核验。E100为E-6正片，不应作C-41负片反相处理。
Provia 100F：原厂120示意显示双边；一侧FUJI RDPIII及批次码，另一侧RDPIII、空心箭头及1–19。具体相位、周期及批次码未实测。https://asset.fujifilm.com/www/us/files/2025-06/76ccbf624f1b5db023c093c94917708f/films_provia-100f_datasheet_01.pdf#page=4
Velvia 100：原厂120示意为单侧1–19、RVP100、FUJI及批次码，另一侧在示意图中空白；不能排除具体批次差异。周期相位未知。https://asset.fujifilm.com/master/emea/files/2020-10/2f3c7f90a0b0c6e605e84f98b7d489c2/films_velvia-100_datasheet_01.pdf#page=6
Pro 400H：原厂120示意为单侧1–19、PRO400H、FUJI及批次码，另一侧在示意图中空白；不能排除具体批次差异。周期相位未知。https://www.fujifilm.com.hk/products/professional_films/pdf/pro_400h_datasheet.pdf#page=6

Fuji批次码并未伪造为真实批次。边字颜色仅为可读性视觉示意，负片经反相和去橙罩后的效果受扫描及处理影响；正片直接显示，其颜色也不能由单一参考推定。完整资料见上级目录 research-20261103-evening/STATUS.txt。外部参考图仅给链接，未取得用于分发的许可。

Runtime v1 contract (2026-11-03): Each PNG losslessly packs the source master's upper 2.5mm and lower 2.5mm rails, in that order, without resizing, repeating, inserting or redrawing content. The source is 820mm long at its authored 6 or 10 px/mm. The same center at 410mm is used by every format. Missing evidence remains missing: empty portions are not asserted to be physically empty. No perforations, light leaks, batch codes or new number sequences were added. manifest.json pins both source and packed hashes.

Nominal windows are 56×41.5, 56×56, 70×56, 84×56 and 168×56mm. The 3mm at either longitudinal end is a design allowance, not a measured camera gate. 645 landscape rotates the strip clockwise; portrait rotates that orientation back. 6×6 orientation is stored explicitly. Rectangular aperture feather is presentation only.

Positive display preserves the authored illustrative RGB. Negative direct route uses the same ink mask with a synthetic orange base and dark lettering (sRGB base 0.58/0.29/0.12, ink 0.14/0.08/0.04). This is a display illustration, not measured transmission, sensitometry or a calibrated inverse scan. E100, Provia and Velvia remain reversal/positive. The existing route and hole-matte preference remain frozen in each snapshot; hole preference has no visual effect because these selected 120 models have no perforations (some historical/converted products do). Asset identity and frozen appearance jointly select these versioned display semantics.

v7 adds three finite reference-guided monochrome strips: Ilford HP5 PLUS and Kodak
T-MAX 100 plus TRI-X 400 (400TX, not 7266). These are self-authored approximations, not photographic pixels or
physically calibrated scans. The T-MAX nominal coverage is only 174.715 mm,
so its 6x17 crop has a narrow model margin. Unknown regions cannot be cropped.
Neutral-gray negative presentation avoids inventing an orange mask for B&W.
See tools/medium120/v7/README.md and docs/120_FILM_AUDIT_20261106.md for all
sources, unsupported-but-real 120 products, and remaining evidence gaps.

V8 (2026-11-06): CineStill800T uses five user-approved visual references. It is an unperforated authored appearance, not an authenticated continuous-roll scan. Four short unobserved seams are explicitly approximated blank; source photos are not packaged. All formats crop the same finite 310.209mm authored span. See tools/medium120/v8/README.md for precise sources, marks and limitations. Original65mm5219 and historical perforated CineStill120 prototypes are separate products.

v9 adds Acros II 120 from the user-approved comparison left panel and a separate
physical-negative photograph. About 59.8mm is directly observed; the finite 180mm
model uses explicitly declared periodic extension for the larger formats. No
first-generation Acros marks, photograph pixels or orange mask are used. Date
and exposure stay centered while conflicting lower marks move outward. See tools/medium120/v9/README.md.

Kodachrome 64 Professional 120 v10: authored KODAK PKR 6033 reverse-panel and PKR/hollow-triangle/index 9 from the Musicians reproduction. All five formats crop one finite 180 mm model. Adjacent indices, cadence, typeface and tint are approximate; only about 60 mm is directly visible. See tools/medium120/v10/README.md and source/evidence manifests. No 135 code 5033, perforations, camera notches or scientific profile changes.
