use serde::Serialize;
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CodecError {
    pub code: &'static str,
    pub message: &'static str,
    pub diagnostic_schema: u8,
    pub reason: Reason,
    pub source: &'static str,
    pub line: u32,
    pub column: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tag: Option<u16>,
    pub stage: &'static str,
}
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Reason {
    Validation, MissingTag, TagShape, TagValue, BlackWhiteLevel, WhiteBalanceRange,
    D65MatrixMissing, MatrixShape, MatrixRange, MatrixSingular, ColorTransform,
    ProfileDimensions, ProfileCurve, ProfileEndpoint, UnsupportedValue, ResourceLimit,
}
impl CodecError {
    /// Only a fixed diagnostic category and an explicitly supported tag ID.
    /// Never attach metadata payloads, paths, or exception strings.
    pub fn detail(mut self, reason: Reason, tag: Option<u16>) -> Self {
        self.reason = reason;
        self.tag = tag.filter(|t| matches!(t, 256|257|258|259|262|266|274|277|278|284|317|322|323|339|33421|33422|50710|50711|50713|50719|50720|50829|50714|50717|50721|50722|50723|50724|50727|50728|50730|50778|50779|50931|50932|50937|50938|50939|50940|50964|50965|50715|50716|50718|50729|50941|50942|50966|50967|50968|50969|50970|50971|50972|50973|51089|51110|51111|330));
        self
    }
}
pub(crate) fn source(file: &str) -> &'static str {
    // Never serialize the compiler's file path. Use this closed public vocabulary.
    if file.ends_with("compat/calibration.rs") { return "COMPAT_CALIBRATION"; }
    if file.ends_with("compat/profiles.rs") { return "COMPAT_PROFILES"; }
    if file.ends_with("compat/lens.rs") { return "COMPAT_LENS"; }
    if file.contains("/compat/") { return "COMPAT"; }
    if file.ends_with("camera_raw/additional/cr3_boxes.rs") { return "CAMERA_RAW_ADDITIONAL_CR3_BOXES"; }
    if file.ends_with("camera_raw/additional.rs") { return "CAMERA_RAW_ADDITIONAL"; }
    if file.ends_with("camera_raw/sony_tiles.rs") { return "CAMERA_RAW_SONY_TILES"; }
    if file.ends_with("camera_raw/canon_stream.rs") { return "CAMERA_RAW_CANON_STREAM"; }
    if file.ends_with("camera_raw/integer_spool.rs") { return "CAMERA_RAW_INTEGER_SPOOL"; }
    if file.ends_with("camera_raw/sony_bayer.rs") { return "CAMERA_RAW_SONY_BAYER"; }
    if file.ends_with("camera_raw/sony_arw6.rs") { return "CAMERA_RAW_SONY_ARW6"; }
    if file.ends_with("camera_raw/fuji/xtrans.rs") { return "CAMERA_RAW_FUJI_XTRANS"; }
    if file.ends_with("camera_raw/fuji.rs") { return "CAMERA_RAW_FUJI"; }
    if file.ends_with("camera_raw/panasonic.rs") { return "CAMERA_RAW_PANASONIC"; }
    match file.rsplit('/').next().unwrap_or("") {
        "dng.rs" => "DNG", "dng_black.rs" => "DNG_BLACK", "tiff.rs" => "TIFF",
        "dng_cfa_color.rs" => "DNG_CFA_COLOR", "dng_color_spec.rs" => "DNG_COLOR_SPEC",
        "profile_develop.rs" => "PROFILE_DEVELOP", "profile_gain_table.rs" => "PROFILE_GAIN_TABLE",
        "raw.rs" => "RAW", "camera_raw.rs" => "CAMERA_RAW",
        "develop.rs" => "DEVELOP", "export.rs" => "EXPORT", "jni_bridge.rs" => "JNI",
        "lossless.rs" => "LOSSLESS",
        "linear_jpeg.rs" => "LINEAR_JPEG",
        "jxl.rs" => "JXL",
        "nikon_he.rs" => "NIKON_HE",
        "nikon_snef.rs" => "NIKON_SNEF",
        "dng_cfa.rs" => "DNG_CFA",
        "dng_bad_pixels.rs" => "DNG_BAD_PIXELS",
        "dng_scale_develop.rs" | "dng_scale_resample.rs" | "dng_geometry_develop.rs" => "DNG_GEOMETRY_DEVELOP",
        "embedded_lens.rs" => "EMBEDDED_LENS",
        "output_color.rs" => "OUTPUT_COLOR",
        "dng_warp.rs" => "DNG_WARP",
        "camera_geometry.rs" => "CAMERA_GEOMETRY",
        "lib.rs" => "LIB",
        "opcodes.rs" => "OPCODES",
        "gainmap.rs" => "GAINMAP",
        "opcode_stage1.rs" => "OPCODE_STAGE1",
        "cfa_gainmap.rs" => "CFA_GAINMAP",
        "technical.rs" => "TECHNICAL",
        _ => "OTHER",
    }
}
pub type Result<T> = std::result::Result<T, CodecError>;
#[track_caller]
pub fn err(code: &'static str, stage: &'static str) -> CodecError {
    let site = std::panic::Location::caller();
    CodecError {
        diagnostic_schema: 1,
        reason: Reason::Validation, source: source(site.file()),
        line: site.line(), column: site.column(), tag: None,
        code,
        stage,
        message: match code {
            "UNSUPPORTED_COLOR_SPACE" => "暂不支持此输出色彩空间，未更改颜色标签冒充支持",
            "UNSUPPORTED_FORMAT" => "暂不支持此照片格式",
            "UNSUPPORTED_COMPRESSION" => {
                "暂不支持此照片的压缩方式"
            }
            "UNSUPPORTED_CAMERA_RAW_VARIANT" => {
                "暂不支持此相机 RAW 像素布局或压缩变体，未使用预览替代"
            }
            "UNSUPPORTED_CAMERA" => "当前离线相机色彩配置尚不支持此 RAW",
            "UNSUPPORTED_DNG_VARIANT" => "暂不支持此 DNG 的像素布局或校正配置",
            "UNSUPPORTED_DNG_OPCODE" => "此DNG包含尚未支持的必需校正，未忽略或替换为预览",
            "UNSUPPORTED_DNG_CALIBRATION" => "暂不支持此 DNG 的相机校准矩阵",
            "INVALID_INPUT" => "照片文件损坏或格式无效",
            "INVALID_COLOR_METADATA" => "RAW 色彩矩阵、白平衡或黑白电平无效",
            "EDGE_LIMIT" => "照片单边超过32768像素上限",
            "PIXEL_LIMIT" => "照片尺寸超出当前支持范围（最多5200万像素）",
            "RESOURCE_LIMIT" => "照片超出当前解码器的文件或元数据资源上限，未判定为损坏",
            "MEMORY_BUDGET" => "可用内存预算不足，未开始解码",
            "DISK_BUDGET" => "本地可用空间不足，未开始解码",
            "NO_SPACE" => "本地空间不足，将尝试清理未完成文件",
            "CANCELLED" => "已取消处理",
            "OUTPUT_EXISTS" => "目标文件已存在，未覆盖",
            "INVALID_PATH" => "照片必须位于应用私有目录",
            "BUSY" => "解码器正在处理另一张照片",
            "INVALID_HANDLE" => "解码器已关闭",
            "IO_ERROR" => "本地照片读写失败",
            "ENCODE_FAILED" => "照片编码失败，将尝试清理未完成文件",
            _ => "照片解码失败",
        },
    }
}
#[track_caller]
pub fn io_error(e: std::io::Error, stage: &'static str) -> CodecError {
    err(
        match e.raw_os_error() {
            Some(libc::ENOSPC) | Some(libc::EDQUOT) => "NO_SPACE",
            _ => match e.kind() {
                std::io::ErrorKind::UnexpectedEof => "INVALID_INPUT",
                std::io::ErrorKind::AlreadyExists => "OUTPUT_EXISTS",
                _ => "IO_ERROR",
            },
        },
        stage,
    )
}

#[cfg(test)]
mod diagnostic_tests {
    use super::*;
    #[track_caller]
    fn helper() -> CodecError { err("INVALID_COLOR_METADATA", "probe") }
    #[test]
    fn fixed_source_never_exposes_paths() {
        assert_eq!(source("/private/home/alice/project/codecs/src/dng.rs"), "DNG");
        assert_eq!(source("/private/location/secret.jpg"), "OTHER");
        assert_eq!(source("src/camera_raw/fuji/xtrans.rs"), "CAMERA_RAW_FUJI_XTRANS");
    }
    #[test]
    fn distinct_callers_and_fixed_details_survive_json() {
        let first = helper();
        let second = helper().detail(Reason::MissingTag, Some(50728));
        assert_ne!(first.line, second.line);
        assert_eq!(first.code, second.code);
        assert_eq!(first.stage, second.stage);
        let json = serde_json::to_value(second).unwrap();
        assert_eq!(json["reason"], "MISSING_TAG");
        assert_eq!(json["tag"], 50728);
        assert!(json["column"].as_u64().unwrap() > 0);
        assert!(!serde_json::to_string(&json).unwrap().contains("/"));
        assert!(serde_json::to_value(helper().detail(Reason::TagValue, Some(34853))).unwrap().get("tag").is_none());
        assert_eq!(serde_json::to_value(helper().detail(Reason::D65MatrixMissing,None)).unwrap()["reason"],"D65_MATRIX_MISSING");
    }
}
