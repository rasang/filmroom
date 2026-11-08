package dev.filmroom.livephoto.core

import java.io.File
import java.io.RandomAccessFile

/** Bounded ISO-BMFF layout validator; codec/sample decoding belongs to the Android media layer. */
internal object Mp4 {
    data class Box(val start: Long, val size: Long, val type: String, val header: Int) { val end get() = start + size; val data get() = start + header }
    fun boxes(f: RandomAccessFile, start: Long, end: Long): List<Box> {
        demand(start >= 0 && end >= start && end <= f.length(), ErrorCode.INVALID_MP4, "Invalid MP4 region")
        val list = mutableListOf<Box>(); var p = start
        while (p < end) {
            demand(end - p >= 8, ErrorCode.INVALID_MP4, "Short MP4 box header")
            val h = f.readAt(p, 8); var n = u32(h, 0); val type = h.copyOfRange(4, 8).toString(Charsets.US_ASCII); var header = 8
            if (n == 1L) {
                demand(end - p >= 16, ErrorCode.INVALID_MP4, "Short extended box")
                f.seek(p + 8); n = f.readLong(); header = 16
            }
            // A zero-sized box consumes EOF: appending a vendor UUID would silently hide it.
            demand(n != 0L, ErrorCode.INVALID_MP4, "size=0 box must be normalized before packaging")
            demand(n >= header && n <= end - p, ErrorCode.INVALID_MP4, "MP4 box size outside parent")
            demand(type != "uuid" || n >= header + 16, ErrorCode.INVALID_MP4, "Short UUID user type")
            list += Box(p, n, type, header); p += n
            demand(list.size <= 10000, ErrorCode.RESOURCE_LIMIT, "Too many MP4 boxes")
        }
        return list
    }
    fun validate(file: File, range: VideoRange = VideoRange(0, file.length())): List<Box> = RandomAccessFile(file, "r").use { f ->
        demand(range.length in 16..SdkLimits.MAX_VIDEO_BYTES && range.offset >= 0 && range.offset <= f.length() - range.length, ErrorCode.INVALID_MP4, "Invalid video size")
        val top = boxes(f, range.offset, range.offset + range.length)
        demand(top.firstOrNull()?.type == "ftyp" && top.first().size >= top.first().header + 8, ErrorCode.INVALID_MP4, "Missing ftyp")
        demand(top.count { it.type == "moov" } == 1 && top.any { it.type == "mdat" && it.size > it.header }, ErrorCode.INVALID_MP4, "Missing movie/media data")
        val moov = top.single { it.type == "moov" }; var videoTracks = 0
        for (trak in boxes(f, moov.data, moov.end).filter { it.type == "trak" }) {
            for (mdia in boxes(f, trak.data, trak.end).filter { it.type == "mdia" }) {
                for (handler in boxes(f, mdia.data, mdia.end).filter { it.type == "hdlr" }) {
                    demand(handler.size >= handler.header + 12, ErrorCode.INVALID_MP4, "Truncated track handler")
                    if (f.readAt(handler.data + 8, 4).contentEquals(ascii("vide"))) videoTracks++
                }
            }
        }
        demand(videoTracks == 1, ErrorCode.UNSUPPORTED_INPUT, "Expected exactly one video track")
        top
    }
}

