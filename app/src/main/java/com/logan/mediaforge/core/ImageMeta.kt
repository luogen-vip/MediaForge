package com.logan.mediaforge.core

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.util.zip.Inflater

/**
 * 图片元信息真实解析（读文件字节，不猜）：
 * - 位深：PNG IHDR / JPEG SOF 精度 / BMP 头 / GIF(8bit 索引)
 * - 色彩模式：灰度 / RGB / RGBA / 索引 / CMYK / YCbCr
 * - 色彩空间：内嵌 ICC Profile 的描述名（PNG iCCP / JPEG APP2），如 sRGB IEC61966-2.1、Display P3
 */
object ImageMeta {

    data class Info(
        val bitDepth: Int? = null,
        val colorMode: String? = null,
        val colorSpace: String? = null,  // ICC 描述名，null=未内嵌或未解析出
        val alpha: Boolean? = null
    ) {
        fun describe(): String {
            val parts = mutableListOf<String>()
            bitDepth?.let { parts += "${it}bit" }
            colorMode?.let { parts += it }
            if (alpha == true) parts += "含透明通道"
            return if (parts.isEmpty()) "未识别" else parts.joinToString(" · ")
        }
    }

    private const val MAX_READ = 512 * 1024

    fun parse(resolver: ContentResolver, uri: Uri): Info? = try {
        resolver.openInputStream(uri)?.use { ins -> parseStream(ins) }
    } catch (e: Exception) {
        null
    }

    fun parse(file: java.io.File): Info? = try {
        file.inputStream().use { ins -> parseStream(ins) }
    } catch (e: Exception) {
        null
    }

    private fun parseStream(ins: InputStream): Info? {
        val buf = ByteArray(MAX_READ)
        var n = 0
        while (n < buf.size) {
            val r = ins.read(buf, n, buf.size - n)
            if (r <= 0) break
            n += r
        }
        if (n < 16) return null
        return parseBytes(buf, n)
    }

    fun parseBytes(b: ByteArray, n: Int): Info? = when {
        n >= 8 && sig(b, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> parsePng(b, n)
        n >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> parseJpeg(b, n)
        n >= 26 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> parseBmp(b, n)
        n >= 6 && sigStr(b, 0, "GIF8") -> Info(bitDepth = 8, colorMode = "索引色（GIF）", alpha = true)
        n >= 12 && sigStr(b, 0, "RIFF") && sigStr(b, 8, "WEBP") -> parseWebp(b, n)
        else -> null
    }

    // ==================== PNG ====================

    private fun parsePng(b: ByteArray, n: Int): Info {
        var bitDepth: Int? = null
        var colorMode: String? = null
        var alpha: Boolean? = null
        var icc: String? = null
        var off = 8
        while (off + 8 <= n) {
            val len = u32be(b, off)
            val type = str(b, off + 4, 4)
            val dataStart = off + 8
            if (dataStart + len > n) break // 头部 512KB 内截断即止
            when (type) {
                "IHDR" -> {
                    if (len >= 10) {
                        bitDepth = b[dataStart + 8].toInt() and 0xFF
                        val ct = b[dataStart + 9].toInt() and 0xFF
                        colorMode = when (ct) {
                            0 -> "灰度"; 2 -> "RGB"; 3 -> "索引色"
                            4 -> "灰度+Alpha"; 6 -> "RGBA"; else -> "类型$ct"
                        }
                        alpha = ct == 4 || ct == 6
                    }
                }
                "tRNS" -> alpha = true
                "iCCP" -> if (icc == null) icc = parseIccp(b, dataStart, len)
                "IDAT", "IEND" -> if (icc != null || bitDepth != null) break
            }
            off = dataStart + len + 4 // 跳过 CRC
            if (type == "IEND") break
        }
        return Info(bitDepth, colorMode ?: "未识别", icc, alpha)
    }

    /** iCCP：名称\0 + 压缩方法(1B) + zlib 数据 → 解压出 ICC Profile */
    private fun parseIccp(b: ByteArray, start: Int, len: Int): String? = try {
        var p = start
        val nameEnd = indexOfZero(b, p, start + len)
        if (nameEnd < 0) null
        else {
            p = nameEnd + 1 + 1 // 跳过名称结束符与压缩方法字节
            val compEnd = start + len
            if (p < compEnd) {
                val inflater = Inflater()
                inflater.setInput(b, p, compEnd - p)
                val out = ByteArray(1 shl 20)
                val got = inflater.inflate(out)
                inflater.end()
                if (got > 132) parseIccDescription(out, got) else null
            } else null
        }
    } catch (e: Exception) { null }

    private fun indexOfZero(b: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to) { if (b[i].toInt() == 0) return i; i++ }
        return -1
    }

    // ==================== JPEG ====================

    private fun parseJpeg(b: ByteArray, n: Int): Info {
        var bitDepth: Int? = null
        var colorMode: String? = null
        var icc: String? = null
        var adobe = false
        var off = 2
        // APP2 ICC 段可能分片，按序号拼接
        var iccData = ByteArray(0)
        var iccTotal = -1
        while (off + 4 <= n) {
            if (b[off] != 0xFF.toByte()) { off++; continue }
            val marker = b[off + 1].toInt() and 0xFF
            if (marker == 0xD8 || (marker in 0xD0..0xD7)) { off += 2; continue } // SOI / RST
            if (marker == 0xDA || marker == 0xD9) break // SOS / EOI：头信息到此为止
            if (off + 4 > n) break
            val segLen = u16be(b, off + 2)
            if (segLen < 2 || off + 2 + segLen > n) break
            val ds = off + 4
            val dlen = segLen - 2
            when (marker) {
                0xE2 -> { // APP2：ICC_PROFILE
                    if (dlen > 14 && sigStr(b, ds, "ICC_PROFILE")) {
                        val total = b[ds + 10].toInt() and 0xFF
                        val cur = b[ds + 11].toInt() and 0xFF
                        iccTotal = total
                        val chunk = b.copyOfRange(ds + 14, off + 2 + segLen)
                        iccData = concat(iccData, chunk)
                        if (cur >= total) {
                            icc = parseIccDescription(iccData, iccData.size)
                            break
                        }
                    }
                }
                0xC0, 0xC1, 0xC2, 0xC3, 0xC9, 0xCA, 0xCB -> { // SOF0-3 / SOF9-11
                    if (dlen >= 6) {
                        bitDepth = b[ds].toInt() and 0xFF
                        val ncomp = b[ds + 5].toInt() and 0xFF
                        colorMode = when (ncomp) {
                            1 -> "灰度"
                            3 -> {
                                // 组件 ID 为 R/G/B 时是 RGB JPEG，否则 YCbCr
                                if (ds + 8 < n && b[ds + 6] == 'R'.code.toByte() &&
                                    b[ds + 7] == 'G'.code.toByte() && b[ds + 8] == 'B'.code.toByte()
                                ) "RGB" else "YCbCr"
                            }
                            4 -> if (adobe) "CMYK(Adobe)" else "CMYK"
                            else -> "${ncomp}通道"
                        }
                    }
                }
                0xEE -> if (dlen >= 5 && sigStr(b, ds, "Adobe")) adobe = true // APP14
            }
            off += 2 + segLen
        }
        if (icc == null && iccTotal > 0 && iccData.isNotEmpty())
            icc = parseIccDescription(iccData, iccData.size)
        return Info(bitDepth, colorMode ?: "未识别", icc, alpha = null)
    }

    // ==================== BMP / WebP ====================

    private fun parseBmp(b: ByteArray, n: Int): Info {
        val bpp = u16le(b, 28)
        val mode = when {
            bpp <= 8 -> "索引色"
            bpp == 16 -> "RGB 5:6:5"
            bpp == 24 -> "RGB"
            bpp == 32 -> "RGBA/RGB"
            else -> null
        }
        return Info(if (bpp > 0) bpp else null, mode ?: "未识别", null, if (bpp == 32) true else null)
    }

    private fun parseWebp(b: ByteArray, n: Int): Info {
        var alpha = false
        if (n >= 30 && sigStr(b, 12, "VP8X")) {
            alpha = (b[20].toInt() and 0x10) != 0
        }
        return Info(8, "RGB" + if (alpha) "A" else "", null, if (alpha) true else null)
    }

    // ==================== ICC Profile 描述名 ====================

    /** 解析 ICC Profile 里的 'desc' 标签（v2 textDescription / v4 mluc），返回色彩空间描述名 */
    private fun parseIccDescription(icc: ByteArray, len: Int): String? = try {
        if (len < 132 || str(icc, 36, 4) != "acsp") null
        else {
            val tagCount = u32be(icc, 128)
            var desc: String? = null
            var i = 0
            while (i < tagCount && 132 + i * 12 + 12 <= len) {
                val sig = str(icc, 132 + i * 12, 4)
                if (sig == "desc") {
                    val off = u32be(icc, 132 + i * 12 + 4).toInt()
                    val size = u32be(icc, 132 + i * 12 + 8).toInt()
                    if (off > 0 && size > 12 && off + size <= len) {
                        val type = str(icc, off, 4)
                        desc = when (type) {
                            "desc" -> { // textDescriptionType：'desc' + 4 保留 + asciiLen + ascii
                                val alen = u32be(icc, off + 8).toInt()
                                if (alen > 0 && off + 12 + alen <= len)
                                    String(icc, off + 12, alen, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
                                else null
                            }
                            "mluc" -> { // multiLocalizedUnicodeType：v4
                                val cnt = u32be(icc, off + 8).toInt()
                                if (cnt > 0 && off + 16 + 12 <= len) {
                                    val recSize = u32be(icc, off + 12).toInt()
                                    val strOff = u32be(icc, off + 12 + 8).toInt()
                                    val strLen = u32be(icc, off + 12 + 4).toInt()
                                    if (recSize >= 12 && strLen > 0 && off + strOff + strLen <= len) {
                                        String(icc, off + strOff, strLen, Charsets.UTF_16BE).trim()
                                            .takeIf { it.isNotEmpty() }
                                    } else null
                                } else null
                            }
                            else -> null
                        }
                    }
                }
                if (desc != null) break
                i++
            }
            desc
        }
    } catch (e: Exception) { null }

    // ==================== 字节工具 ====================

    private fun sig(b: ByteArray, off: Int, vararg s: Int): Boolean =
        off + s.size <= b.size && s.indices.all { (b[off + it].toInt() and 0xFF) == s[it] }

    private fun sigStr(b: ByteArray, off: Int, s: String): Boolean {
        if (off + s.length > b.size) return false
        return s.indices.all { b[off + it] == s[it].code.toByte() }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        if (off + len > b.size) return ""
        return String(b, off, len, Charsets.US_ASCII)
    }

    private fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val r = ByteArray(a.size + b.size)
        a.copyInto(r); b.copyInto(r, a.size)
        return r
    }
}
