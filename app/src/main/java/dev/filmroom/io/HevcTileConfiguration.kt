package dev.filmroom.io

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A conservative admission check for the smaller, per-tile HEVC allocation budget.
 * This is NOT a HEVC decoder or a proof that CABAC data describes a valid picture.
 * Unsupported syntax is rejected and must never gain this smaller allocation budget.
 *
 * hvcC is the configuration record WITHOUT the enclosing box header. The payload stream
 * is not closed and is never read past payloadLength. I/O and cancellation exceptions
 * propagate; malformed or unsupported bytes return false. Validation consumes the stream.
 */
internal object HevcTileConfiguration {
    private const val MAX_PIXELS = 1_048_576L
    private const val MAX_EDGE = 2048
    private const val MAX_CONFIGURATION = 65_536
    private const val MAX_PARAMETER_SET = 4096
    private const val MAX_SEI = 32_768
    private const val MAX_PAYLOAD = 16L * 1024 * 1024
    private const val MAX_NAL = 8L * 1024 * 1024
    private const val MAX_NALS = 64
    private const val MAX_SLICES = 32

    fun validate(
        hvcc: ByteArray,
        payload: InputStream,
        payloadLength: Long,
        width: Int,
        height: Int,
        check: () -> Unit,
    ): Boolean {
        check()
        return try {
            ensure(width in 1..MAX_EDGE && height in 1..MAX_EDGE)
            ensure(width.toLong() * height <= MAX_PIXELS)
            ensure(payloadLength in 1..MAX_PAYLOAD)
            val configuration = configuration(hvcc, check)
            val vps = vps(configuration.vps, configuration.profile, check)
            val sps = sps(configuration.sps, configuration.profile, vps, width, height, check)
            val pps = pps(configuration.pps, sps, check)
            picture(payload, payloadLength, configuration.lengthSize, sps, pps, check)
            true
        } catch (_: Unsupported) {
            false
        }
    }

    private class Unsupported : RuntimeException(null, null, false, false)
    private fun ensure(value: Boolean) { if (!value) throw Unsupported() }

    private data class Configuration(
        val profile: ByteArray, val lengthSize: Int,
        val vps: ByteArray, val sps: ByteArray, val pps: ByteArray,
    )
    private data class Ordering(val bufferingMinusOne: Int, val reorder: Int, val latency: Int)
    private data class Vps(val id: Int, val ordering: Ordering)
    private data class Sps(
        val id: Int, val minCbLog2: Int, val ctbLog2: Int, val ctbCount: Int,
        val sao: Boolean,
    )
    private data class Pps(
        val id: Int, val outputFlagPresent: Boolean, val extraHeaderBits: Int,
        val initQp: Int, val cbOffset: Int, val crOffset: Int,
        val chromaOffsetsPresent: Boolean, val loopFilterAcrossSlices: Boolean,
        val deblockingOverride: Boolean, val deblockingDisabled: Boolean,
    )

    private fun configuration(bytes: ByteArray, check: () -> Unit): Configuration {
        ensure(bytes.size in 23..MAX_CONFIGURATION)
        fun u(at: Int) = bytes[at].toInt() and 255
        ensure(u(0) == 1)
        val profile = bytes.copyOfRange(1, 13)
        validateProfile(profile)
        ensure(u(13) and 0xf0 == 0xf0)
        ensure(u(15) and 0xfc == 0xfc)
        ensure(u(16) == 0xfd && u(17) == 0xf8 && u(18) == 0xf8) // 8-bit 4:2:0
        // hvcC may leave temporal-layer summaries unspecified (zero / not nested).
        // The actual VPS/SPS and every NAL still have to prove one nested base layer.
        ensure(((u(21) ushr 3) and 7) in 0..1)
        val lengthSize = (u(21) and 3) + 1
        ensure(lengthSize != 3) // lengthSizeMinusOne == 2 is reserved
        ensure(u(22) in 3..5)
        var at = 23
        fun byte(): Int { ensure(at < bytes.size); return u(at++) }
        fun short() = (byte() shl 8) or byte()
        val parameters = HashMap<Int, ByteArray>()
        val arrays = HashSet<Int>()
        repeat(u(22)) {
            check()
            val header = byte()
            ensure(header and 0x40 == 0)
            val type = header and 63
            ensure(type in setOf(32, 33, 34, 39, 40) && arrays.add(type))
            val count = short()
            ensure(count in 1..8)
            if (type in 32..34) ensure(count == 1 && header and 0x80 != 0)
            repeat(count) {
                val size = short()
                ensure(size in 3..(if (type in 32..34) MAX_PARAMETER_SET else MAX_SEI))
                ensure(size <= bytes.size - at)
                val nal = bytes.copyOfRange(at, at + size)
                at += size
                val source = Input(ByteArrayInputStream(nal), nal.size.toLong(), check)
                ensure(nalHeader(source) == type)
                if (type in 32..34) parameters[type] = nal else sei(Bits(source))
            }
        }
        ensure(at == bytes.size && parameters.keys == setOf(32, 33, 34))
        return Configuration(profile, lengthSize, parameters.getValue(32), parameters.getValue(33), parameters.getValue(34))
    }

    private fun validateProfile(profile: ByteArray) {
        fun u(at: Int) = profile[at].toInt() and 255
        ensure(profile.size == 12)
        ensure(u(0) ushr 6 == 0 && u(0) and 31 in setOf(1, 3)) // Main / Main Still Picture
        // Progressive, non-interlaced, frame-only. Reserved constraint bits are zero.
        ensure(u(5) and 0xd0 == 0x90 && u(5) and 15 == 0)
        ensure((6..9).all { u(it) == 0 } && u(10) and 0xfe == 0)
        ensure(u(11) in setOf(30, 60, 63, 90, 93, 120, 123, 150, 153))
    }

    private fun profile(bits: Bits, expected: ByteArray) {
        val actual = ByteArray(12) { bits.read(8).toByte() }
        validateProfile(actual)
        ensure(actual.contentEquals(expected))
    }

    private fun ordering(bits: Bits): Ordering {
        bits.flag() // sub_layer_ordering_info_present_flag; only layer zero exists
        val buffering = bits.ue(5) // at most six decoded pictures, including the current one
        val reorder = bits.ue(5)
        ensure(reorder <= buffering)
        return Ordering(buffering, reorder, bits.ue(65_535))
    }

    private fun parameter(bytes: ByteArray, type: Int, check: () -> Unit): Bits {
        val input = Input(ByteArrayInputStream(bytes), bytes.size.toLong(), check)
        ensure(nalHeader(input) == type)
        return Bits(input)
    }

    private fun vps(bytes: ByteArray, expected: ByteArray, check: () -> Unit): Vps {
        val b = parameter(bytes, 32, check)
        val id = b.read(4)
        ensure(b.read(2) == 3) // internal and available base layer
        ensure(b.read(6) == 0 && b.read(3) == 0 && b.flag())
        ensure(b.read(16) == 0xffff)
        profile(b, expected)
        val order = ordering(b)
        ensure(b.read(6) == 0 && b.ue(0) == 0) // no additional layer sets
        if (b.flag()) {
            ensure(b.readLong(32) != 0L && b.readLong(32) != 0L)
            if (b.flag()) b.ue(65_535)
            ensure(b.ue(0) == 0) // HRD parsing is outside this narrow admission path
        }
        ensure(!b.flag()) // vps_extension_flag
        b.trailing()
        return Vps(id, order)
    }

    private fun sps(bytes: ByteArray, expected: ByteArray, vps: Vps, width: Int, height: Int, check: () -> Unit): Sps {
        val b = parameter(bytes, 33, check)
        ensure(b.read(4) == vps.id && b.read(3) == 0 && b.flag())
        profile(b, expected)
        val id = b.ue(15)
        ensure(b.ue(3) == 1)
        // Check coded dimensions, not just conformance-window/display dimensions.
        ensure(b.ue(MAX_EDGE) == width && b.ue(MAX_EDGE) == height)
        if (b.flag()) repeat(4) { ensure(b.ue(0) == 0) }
        ensure(b.ue(0) == 0 && b.ue(0) == 0)
        b.ue(12) // log2_max_pic_order_cnt_lsb_minus4
        ensure(ordering(b) == vps.ordering)
        val minCb = b.ue(3) + 3
        val ctb = minCb + b.ue(3)
        ensure(ctb in 4..6 && width % (1 shl minCb) == 0 && height % (1 shl minCb) == 0)
        val minTb = b.ue(3) + 2
        val maxTb = minTb + b.ue(3)
        ensure(minTb <= minCb && maxTb <= minOf(ctb, 5))
        b.ue(ctb - minTb) // max_transform_hierarchy_depth_inter
        b.ue(ctb - minTb) // max_transform_hierarchy_depth_intra
        ensure(!b.flag()) // scaling lists are deliberately unsupported
        b.flag() // asymmetric motion partitions
        val sao = b.flag()
        ensure(!b.flag()) // PCM
        // A single explicitly empty RPS is equivalent to declaring no reference pictures.
        // At index zero there is no inter_ref_pic_set_prediction_flag to infer or skip.
        if (b.ue(1) == 1) { b.ue(0); b.ue(0) } // num_negative_pics / num_positive_pics
        ensure(!b.flag()) // no long-term reference picture sets
        b.flag() // temporal MVP; no inter prediction is admitted in the IDR payload
        b.flag() // strong intra smoothing
        if (b.flag()) vui(b)
        ensure(!b.flag()) // SPS extensions, including multilayer/SCC/range extensions
        b.trailing()
        val edge = 1 shl ctb
        return Sps(id, minCb, ctb, ((width + edge - 1) / edge) * ((height + edge - 1) / edge), sao)
    }

    private fun vui(b: Bits) {
        if (b.flag()) ensure(b.read(8) in 0..1) // unspecified or square-pixel SAR
        if (b.flag()) b.flag() // overscan
        if (b.flag()) {
            b.read(3) // video format
            b.flag() // full range; the enclosing container also validates color semantics
            if (b.flag()) repeat(3) { b.read(8) }
        }
        if (b.flag()) { b.ue(5); b.ue(5) }
        b.flag() // neutral_chroma_indication_flag
        ensure(!b.flag()) // field_seq_flag
        b.flag() // frame_field_info_present_flag
        ensure(!b.flag()) // default display window would change the tile geometry
        if (b.flag()) {
            ensure(b.readLong(32) != 0L && b.readLong(32) != 0L)
            if (b.flag()) b.ue(65_535)
            ensure(!b.flag()) // vui_hrd_parameters_present_flag
        }
        if (b.flag()) {
            b.read(3)
            b.ue(4095)
            b.ue(16); b.ue(16); b.ue(16); b.ue(16)
        }
    }

    private fun pps(bytes: ByteArray, sps: Sps, check: () -> Unit): Pps {
        val b = parameter(bytes, 34, check)
        val id = b.ue(63)
        ensure(b.ue(15) == sps.id && !b.flag()) // no dependent slice segments
        val output = b.flag()
        val extra = b.read(3)
        b.flag(); b.flag() // sign data hiding, CABAC init flag presence
        b.ue(5); b.ue(5)
        val qp = 26 + b.se(-26, 25)
        b.flag(); b.flag() // constrained intra prediction, transform skip
        if (b.flag()) b.ue(sps.ctbLog2 - sps.minCbLog2)
        val cb = b.se(-12, 12)
        val cr = b.se(-12, 12)
        val offsets = b.flag()
        b.read(3) // weighted prediction flags, transquant bypass
        ensure(!b.flag() && !b.flag()) // internal codec tiles and WPP are unsupported here
        val loopFilter = b.flag()
        var canOverride = false
        var disabled = false
        if (b.flag()) {
            canOverride = b.flag()
            disabled = b.flag()
            if (!disabled) { b.se(-6, 6); b.se(-6, 6) }
        }
        ensure(!b.flag()) // pps_scaling_list_data_present_flag
        b.flag() // lists_modification_present_flag
        b.ue(sps.ctbLog2 - 2)
        ensure(!b.flag() && !b.flag()) // slice header extension, PPS extension
        b.trailing()
        return Pps(id, output, extra, qp, cb, cr, offsets, loopFilter, canOverride, disabled)
    }

    private fun picture(input: InputStream, length: Long, lengthSize: Int, sps: Sps, pps: Pps, check: () -> Unit) {
        val source = Input(input, length, check)
        var nals = 0
        var slices = 0
        var pictureType = -1
        var noPriorOutput: Boolean? = null
        var previousAddress = -1
        var suffix = false
        while (source.remaining > 0) {
            check()
            ensure(++nals <= MAX_NALS)
            var size = 0L
            repeat(lengthSize) { size = (size shl 8) or source.byte().toLong() }
            ensure(size in 3..MAX_NAL && size <= source.remaining)
            val nal = Window(source, size)
            val type = nalHeader(nal)
            val b = Bits(nal)
            when (type) {
                19, 20 -> {
                    ensure(!suffix && ++slices <= MAX_SLICES)
                    if (pictureType < 0) pictureType = type
                    ensure(type == pictureType)
                    val first = b.flag()
                    ensure(first == (slices == 1)) // exactly one first slice, at the start
                    val noOutput = b.flag() // IDR does not reference prior pictures
                    if (noPriorOutput == null) noPriorOutput = noOutput
                    ensure(noPriorOutput == noOutput)
                    ensure(b.ue(63) == pps.id)
                    val address = if (first) 0 else b.read(ceilLog2(sps.ctbCount))
                    ensure(address > previousAddress && address < sps.ctbCount)
                    previousAddress = address
                    repeat(pps.extraHeaderBits) { ensure(!b.flag()) }
                    ensure(b.ue(2) == 2) // independent I slice only
                    if (pps.outputFlagPresent) ensure(b.flag())
                    var sao = false
                    if (sps.sao) { val luma = b.flag(); val chroma = b.flag(); sao = luma || chroma }
                    ensure(pps.initQp + b.se(-51, 51) in 0..51)
                    if (pps.chromaOffsetsPresent) {
                        ensure(pps.cbOffset + b.se(-12, 12) in -12..12)
                        ensure(pps.crOffset + b.se(-12, 12) in -12..12)
                    }
                    var disabled = pps.deblockingDisabled
                    if (pps.deblockingOverride && b.flag()) {
                        disabled = b.flag()
                        if (!disabled) { b.se(-6, 6); b.se(-6, 6) }
                    }
                    if (pps.loopFilterAcrossSlices && (sao || !disabled)) b.flag()
                    b.alignment()
                    ensure(nal.remaining > 0) // nonempty CABAC data; platform decoder validates it
                    b.drain()
                }
                39, 40 -> {
                    ensure(size <= MAX_SEI)
                    if (type == 39) ensure(slices == 0 && !suffix)
                    else { ensure(slices > 0); suffix = true }
                    sei(b)
                }
                else -> throw Unsupported() // includes in-band VPS/SPS/PPS, CRA, inter pictures and AUD
            }
            ensure(nal.remaining == 0L)
        }
        ensure(slices > 0)
    }

    private fun ceilLog2(value: Int): Int = if (value <= 1) 0 else 32 - Integer.numberOfLeadingZeros(value - 1)

    private fun nalHeader(input: Bytes): Int {
        val a = input.byte()
        val b = input.byte()
        ensure(a and 0x81 == 0 && b == 1) // forbidden bit zero, layer zero, temporal_id_plus1 == 1
        return (a ushr 1) and 63
    }

    /** Only unregistered user data is admitted: no HDR, recovery, timing or other SEI semantics. */
    private fun sei(b: Bits) {
        var messages = 0
        while (true) {
            val first = b.read(8)
            if (first == 0x80 && b.exhausted()) return // rbsp_trailing_bits
            ensure(++messages <= 8 && first == 5)
            var size = 0
            var next: Int
            do {
                next = b.read(8)
                size += next
                ensure(size <= MAX_SEI)
            } while (next == 255)
            ensure(size >= 16) // uuid_iso_iec_11578
            repeat(size) { b.read(8) }
        }
    }

    private interface Bytes {
        val remaining: Long
        fun byte(): Int
    }

    /** Bounded buffered reader: even refill never consumes bytes belonging to the next item. */
    private class Input(private val input: InputStream, length: Long, private val check: () -> Unit) : Bytes {
        private val buffer = ByteArray(8192)
        private var at = 0
        private var end = 0
        private var unread = length
        override val remaining: Long get() = unread + end - at
        override fun byte(): Int {
            if (at == end) {
                ensure(unread > 0)
                check()
                val count = input.read(buffer, 0, minOf(unread, buffer.size.toLong()).toInt())
                ensure(count >= 0)
                if (count == 0) {
                    val value = input.read()
                    ensure(value >= 0)
                    buffer[0] = value.toByte()
                    end = 1
                } else end = count
                at = 0
                unread -= end
            }
            return buffer[at++].toInt() and 255
        }
    }

    private class Window(private val source: Bytes, length: Long) : Bytes {
        override var remaining = length
            private set
        override fun byte(): Int {
            ensure(remaining > 0)
            val value = source.byte()
            remaining--
            return value
        }
    }

    /** Removes and validates EBSP escape bytes while reading; never allocates a NAL-sized buffer. */
    private class Bits(private val input: Bytes) {
        private var zeros = 0
        private var current = 0
        private var available = 0
        private fun next(): Int {
            var value = input.byte()
            if (zeros == 2) {
                ensure(value >= 3)
                if (value == 3) {
                    value = input.byte()
                    ensure(value <= 3)
                    zeros = 0
                }
            }
            zeros = if (value == 0) zeros + 1 else 0
            return value
        }
        fun read(count: Int): Int {
            ensure(count in 0..30)
            var result = 0
            repeat(count) {
                if (available == 0) { current = next(); available = 8 }
                result = (result shl 1) or ((current ushr --available) and 1)
            }
            return result
        }
        fun readLong(count: Int): Long {
            var result = 0L
            repeat(count) { result = (result shl 1) or read(1).toLong() }
            return result
        }
        fun flag() = read(1) != 0
        fun ue(maximum: Int): Int {
            var leading = 0
            while (!flag()) { ensure(++leading <= 30) }
            val value = (1L shl leading) - 1 + read(leading)
            ensure(value <= maximum)
            return value.toInt()
        }
        fun se(minimum: Int, maximum: Int): Int {
            val encoded = ue(2 * maxOf(-minimum, maximum))
            val value = if (encoded and 1 == 0) -(encoded / 2) else (encoded + 1) / 2
            ensure(value in minimum..maximum)
            return value
        }
        fun alignment() {
            ensure(flag())
            while (available > 0) ensure(!flag())
        }
        fun trailing() { alignment(); ensure(exhausted()) }
        fun exhausted() = available == 0 && input.remaining == 0L
        fun drain() {
            ensure(available == 0)
            while (input.remaining > 0) next()
        }
    }
}
