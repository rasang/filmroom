use serde::Serialize;
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CodecError {
    pub code: &'static str,
    pub message: &'static str,
    pub stage: &'static str,
}
pub type Result<T> = std::result::Result<T, CodecError>;
pub fn err(code: &'static str, stage: &'static str) -> CodecError {
    CodecError {
        code,
        stage,
        message: match code {
            "UNSUPPORTED_COLOR_SPACE" => "暂不支持此输出色彩空间，未更改颜色标签冒充支持",
            "UNSUPPORTED_FORMAT" => "暂不支持此照片格式",
            "UNSUPPORTED_COMPRESSION" => {
                "暂不支持此 RAW 压缩变体；当前支持已验证的无压缩及无损 JPEG DNG"
            }
            "UNSUPPORTED_DNG_VARIANT" => "暂不支持此 DNG 的像素布局或校正配置",
            "UNSUPPORTED_DNG_OPCODE" => "此DNG包含尚未支持的必需校正，未忽略或替换为预览",
            "UNSUPPORTED_DNG_CALIBRATION" => "暂不支持此 DNG 的相机校准矩阵",
            "INVALID_INPUT" => "照片文件损坏或格式无效",
            "INVALID_COLOR_METADATA" => "RAW 色彩矩阵、白平衡或黑白电平无效",
            "PIXEL_LIMIT" => "照片尺寸超出当前支持范围（最多5200万像素）",
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
