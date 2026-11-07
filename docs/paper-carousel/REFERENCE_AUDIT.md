# 相纸模型包装审核 / 2026-11-07

现有目录共 10 项：7 相纸 + 3 电影拷贝片，不能全部画成胶卷或相纸。
参考图只用于视觉核对；应用缩略图重新绘制文字、建立包装几何，不直接使用参考照片像素。
尺寸为缩略图归一化，不声称精确产品毫米尺寸。批次条码、序号、奥运标识不复制，不造假。

## 已查看实际产品图
|Profile|包装|参考|
|---|---|---|
|kodak_portra_endura|黄薄盒、红 Kodak 灰 Professional、底部规格带|https://www.adorama.com/kkpe810100n.html|
|kodak_supra_endura|黄薄盒、底部蓝灰规格带|https://negativedevelopment.co.uk/products/8x10-expired-kodak-supra-endura-10-sheets|
|kodak_ultra_endura|牛皮纸长卷箱、橙规格标签|https://www.adorama.com/kkue40164n.html|
|kodak_endura_premier|牛皮纸卷箱、白标签黄标题|https://www.instructables.com/Enlarging-the-Everyday-Chromogenic-Printing-Explai/|
|kodak_ektacolor_edge|牛皮纸箱、白规格标签|https://www.uniquephoto.com/product/kodak-edge-12x305-e-3934502|
|fujifilm_crystal_archive_typeii|棕卷箱、绿色文字|https://www.adorama.com/fo7105987.html|
|ilford_multigrade_iv_rc|白盒、深色块与粉色规格带，必须 IV 代而非 V 代|https://www.mbtech.fr/papier-photo-noir-et-blanc/671-papier-argentique-noir-et-blanc-ilford-mg-iv-rc-de-luxe-brillant-1m-1769900-100-feuilles-190-g-m2-127x178cm.html|

## 电影拷贝片，历史外包装细节待核
2383 / 2393 / 2302 产品身份由 Kodak 官方资料确认。2393 是 Vision Premier，2302 黑白，不是彩色相纸。
Kodak 2021 官方通告确认 2383 / 2302 35 mm × 2000 ft 曾由罐改为真空袋：
https://www.kodak.com/content/pdfs/motion/PCN040621-2K-VCP-BWP-Vac-Bag-ND.pdf

本稿三款采用历史罐形，文字为可读性简化；**未核实这三款各自的历史罐盖标签配色/排版，不宣称实物精确复刻**。不能把 VISION3 500T 拍摄负片图冒充这几款印片。
2393 产品技术资料： https://megatheriid.com/docs/motionpicture/kodak/lab/lab_h12393t.pdf
2302：https://www.kodak.com/en/motion/product/post/print-films/black-and-white-2302-3302/
2383：https://www.kodak.com/en/motion/product/post/print-films/vision-color-2383-3383/

## 实施状态
- 独立分支 feature/paper-model-carousel；基线 beta4 8ac5ade7，不并入当前 beta4。
- 复用 FilmStockScrollView + retainStrip，点按选择，滑动不选中；保留原 10 个 profile ID 与 PrintMediumLabels。
- 保留 direct / 不可编辑禁用、黑白相纸提示及旧设置存储行为。
- 预留 none 选项，由独立 film/paper-none 生产渲染接入完成后才能启用交付。目前不能以本分支发布。
- Blender 按构建 owner 要求等待释放内存窗口，尚未生成最终模型图，不能说建模已审核。

后续验证清单：等共享构建窗口释放后渲染并查看10张（罐形3项必须标待核）；将成图缩成240px透明WebP；更新现有 FilmStockArtworkHostTest 的“2302不可有缩略图”旧断言，新增全部10项、选择保留、不可编辑/direct、横竖屏、大字体、滚动不误选、反复进入/关闭测试；等 none 生产实现再做四组合回归。当前仅 test_static.py 通过，不等于 JVM/native/整包通过。

## 渲染更新
10 张 384px 模型已完成并目视复核，240px 透明 WebP 总计约280KiB。
审图为 paper-model-review.png（7款实物依据相纸）和 print-film-model-review.png（3款标签待核历史罐示意）。
原胶卷相机/灯光基础上增加相纸顶面补光，纸箱归一尺寸，不声称精确工业尺寸。已修首轮盒盖与箱顶共面导致的黑面、圆罐错误方标签。
film_none 暂在 paper_carousel.xml 定义以让独立树有资源；与 none 分支合并时删除本文件中的 film_none（该分支 strings.xml 已拥有它），保留 paper_none。
已同步用户accept的 b1dfe380 NoFilmArtwork。UI旧弹窗测试已迁到横滑点选测试，尚未执行；需要整合none生产路径再测四组合。
