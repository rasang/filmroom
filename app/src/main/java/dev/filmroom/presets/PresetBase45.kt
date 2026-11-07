package dev.filmroom.presets

/** RFC 9285 alphabet keeps QR payloads in the more efficient alphanumeric mode. */
internal object PresetBase45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    fun encode(bytes: ByteArray): String = buildString((bytes.size * 3 + 1) / 2) {
        var index = 0
        while (index < bytes.size) {
            val pair = index + 1 < bytes.size
            val value = if (pair) (bytes[index].toInt() and 255) * 256 + (bytes[index + 1].toInt() and 255) else bytes[index].toInt() and 255
            append(ALPHABET[value % 45]); append(ALPHABET[value / 45 % 45])
            if (pair) append(ALPHABET[value / (45 * 45)])
            index += if (pair) 2 else 1
        }
    }
    fun decode(text: String): ByteArray {
        require(text.length % 3 != 1)
        val output = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
        var at = 0; var out = 0
        fun digit(index: Int): Int = ALPHABET.indexOf(text[index]).also { require(it >= 0) }
        while (at < text.length) {
            val triple = at + 2 < text.length
            val value = digit(at) + 45 * digit(at + 1) + if (triple) 45 * 45 * digit(at + 2) else 0
            if (triple) { require(value <= 65535); output[out++] = (value / 256).toByte(); output[out++] = value.toByte() }
            else { require(value <= 255); output[out++] = value.toByte() }
            at += if (triple) 3 else 2
        }
        require(encode(output) == text)
        return output
    }
}
