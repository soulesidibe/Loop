package loop.store

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The gzip members of a file of concatenated members, as [GzipAttemptRecords] writes it: one member per
 * append (m2-18). Unlike `GZIPInputStream`, which throws on the first cut or changed member and loses the
 * whole file, this keeps every member that decodes and checks out (CRC32 and size), and skips the rest: a
 * bad stretch counts once in [damaged], and decoding goes on at the next gzip header after it. A kill or a
 * full disk in the middle of an append leaves such a cut member, and so does reading a live Attempt file.
 */
internal class GzipMembers private constructor(
    /** The decompressed bytes of each good member, in file order. */
    val members: List<ByteArray>,
    /** How many stretches of the file were cut or unreadable and left out. */
    val damaged: Int,
) {
    companion object {
        /**
         * Splits [bytes] into members. A file that does not start with a gzip header is not one of these
         * files at all, and fails with an [IOException]; an empty one has no members.
         */
        fun decode(bytes: ByteArray): GzipMembers {
            if (bytes.isEmpty()) return GzipMembers(emptyList(), 0)
            if (!isHeaderAt(bytes, 0)) throw IOException("not a gzip file")
            val members = mutableListOf<ByteArray>()
            var damaged = 0
            var offset = 0
            var inDamage = false
            while (offset < bytes.size) {
                val member = memberAt(bytes, offset)
                if (member != null) {
                    members += member.data
                    offset = member.end
                    inDamage = false
                } else {
                    if (!inDamage) damaged++
                    inDamage = true
                    offset = nextHeader(bytes, offset + 1)
                }
            }
            return GzipMembers(members, damaged)
        }

        private class Member(
            val data: ByteArray,
            val end: Int,
        )

        /** The member that starts at [start] and its end offset, or null when it is cut, changed or not a member. */
        private fun memberAt(
            bytes: ByteArray,
            start: Int,
        ): Member? {
            if (!isHeaderAt(bytes, start) || bytes.size - start < HEADER_SIZE) return null
            val flags = bytes[start + 3].toInt() and 0xff
            var pos = start + HEADER_SIZE
            if (flags and FEXTRA != 0) {
                if (pos + 2 > bytes.size) return null
                pos += 2 + u16(bytes, pos)
            }
            if (flags and FNAME != 0) pos = afterZero(bytes, pos) ?: return null
            if (flags and FCOMMENT != 0) pos = afterZero(bytes, pos) ?: return null
            if (flags and FHCRC != 0) pos += 2
            if (pos > bytes.size) return null
            val inflater = Inflater(true)
            try {
                inflater.setInput(bytes, pos, bytes.size - pos)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    // No output and not finished with the rest of the file as input: the member is cut.
                    if (n == 0) return null
                    out.write(buffer, 0, n)
                }
                val trailer = bytes.size - inflater.remaining
                if (trailer + TRAILER_SIZE > bytes.size) return null
                val data = out.toByteArray()
                val crc = CRC32().apply { update(data) }.value
                if (u32(bytes, trailer) != crc || u32(bytes, trailer + 4) != (data.size.toLong() and 0xffffffffL)) return null
                return Member(data, trailer + TRAILER_SIZE)
            } catch (e: DataFormatException) {
                return null
            } finally {
                inflater.end()
            }
        }

        private fun isHeaderAt(
            bytes: ByteArray,
            at: Int,
        ): Boolean =
            at + 2 < bytes.size &&
                bytes[at] == MAGIC_1 &&
                bytes[at + 1] == MAGIC_2 &&
                bytes[at + 2] == DEFLATE

        /** The offset of the next gzip header at or after [from], or the end of [bytes]. */
        private fun nextHeader(
            bytes: ByteArray,
            from: Int,
        ): Int = (from until bytes.size).firstOrNull { isHeaderAt(bytes, it) } ?: bytes.size

        /** The offset after the zero byte that ends a header string at [from], or null when there is none. */
        private fun afterZero(
            bytes: ByteArray,
            from: Int,
        ): Int? = (from until bytes.size).firstOrNull { bytes[it] == ZERO }?.let { it + 1 }

        private fun u16(
            bytes: ByteArray,
            at: Int,
        ): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

        private fun u32(
            bytes: ByteArray,
            at: Int,
        ): Long = (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)

        private const val MAGIC_1 = 0x1f.toByte()
        private const val MAGIC_2 = 0x8b.toByte()
        private const val DEFLATE = 8.toByte()
        private const val ZERO = 0.toByte()
        private const val HEADER_SIZE = 10
        private const val TRAILER_SIZE = 8
        private const val BUFFER_SIZE = 8192
        private const val FHCRC = 0x02
        private const val FEXTRA = 0x04
        private const val FNAME = 0x08
        private const val FCOMMENT = 0x10
    }
}
