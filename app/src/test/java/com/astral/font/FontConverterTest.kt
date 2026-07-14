package com.astral.font

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class FontConverterTest {

    @Test
    fun testFontConverter_Italic() {
        val mockFontBytes = createMockFontBytes(includeGlyf = false)
        val inputStream = ByteArrayInputStream(mockFontBytes)
        val outputStream = ByteArrayOutputStream()

        // Convert mock font to Italic
        FontConverter.convertFont(inputStream, outputStream, FontConverter.Style.ITALIC)
        val resultBytes = outputStream.toByteArray()

        assertNotNull(resultBytes)
        assertTrue(resultBytes.size > 12)

        // Verify it parses successfully and we can extract the updated values
        verifyFontAttributes(resultBytes, FontConverter.Style.ITALIC)
    }

    @Test
    fun testFontConverter_Bold() {
        val mockFontBytes = createMockFontBytes(includeGlyf = false)
        val inputStream = ByteArrayInputStream(mockFontBytes)
        val outputStream = ByteArrayOutputStream()

        // Convert mock font to Bold
        FontConverter.convertFont(inputStream, outputStream, FontConverter.Style.BOLD)
        val resultBytes = outputStream.toByteArray()

        assertNotNull(resultBytes)
        assertTrue(resultBytes.size > 12)

        // Verify it parses successfully and we can extract the updated values
        verifyFontAttributes(resultBytes, FontConverter.Style.BOLD)
    }

    @Test
    fun testFontConverter_BoldItalic() {
        val mockFontBytes = createMockFontBytes(includeGlyf = false)
        val inputStream = ByteArrayInputStream(mockFontBytes)
        val outputStream = ByteArrayOutputStream()

        // Convert mock font to Bold Italic
        FontConverter.convertFont(inputStream, outputStream, FontConverter.Style.BOLD_ITALIC)
        val resultBytes = outputStream.toByteArray()

        assertNotNull(resultBytes)
        assertTrue(resultBytes.size > 12)

        // Verify it parses successfully and we can extract the updated values
        verifyFontAttributes(resultBytes, FontConverter.Style.BOLD_ITALIC)
    }

    @Test
    fun testFontConverter_WithGlyfAndHmtx() {
        val mockFontBytes = createMockFontBytes(includeGlyf = true)
        val inputStream = ByteArrayInputStream(mockFontBytes)
        val outputStream = ByteArrayOutputStream()

        // Convert mock font with glyf to Bold Italic
        FontConverter.convertFont(inputStream, outputStream, FontConverter.Style.BOLD_ITALIC)
        val resultBytes = outputStream.toByteArray()

        assertNotNull(resultBytes)
        assertTrue(resultBytes.size > 12)

        // Verify font attributes and verify glyf/hmtx tables are updated
        verifyFontAttributes(resultBytes, FontConverter.Style.BOLD_ITALIC)

        // Find hmtx table and verify metrics scaled
        val numTables = readUInt16(resultBytes, 4)
        var index = 12
        var foundHmtx = false
        var foundGlyf = false
        val foundTags = ArrayList<String>()
        for (i in 0 until numTables) {
            val tag = String(resultBytes, index, 4, StandardCharsets.US_ASCII)
            foundTags.add(tag)
            val offset = readUInt32(resultBytes, index + 8).toInt()
            val length = readUInt32(resultBytes, index + 12).toInt()
            val data = resultBytes.copyOfRange(offset, offset + length)

            if (tag == "hmtx") {
                foundHmtx = true
                // First glyph metric
                val advanceWidth = readUInt16(data, 0)
                val lsb = readInt16(data, 2)
                // Originally width 1000, lsb 50. Bold Italic: scaled by 1.15 -> width 1150, lsb is around 57 or 58
                assertEquals("advanceWidth mismatch: ", 1150, advanceWidth)
                assertTrue("lsb mismatch: got $lsb", lsb == 57 || lsb == 58)
            }
            if (tag == "glyf") {
                foundGlyf = true
                assertTrue(length > 0)
            }
            index += 16
        }
        assertTrue("hmtx not found in tags: $foundTags", foundHmtx)
        assertTrue("glyf not found in tags: $foundTags", foundGlyf)
    }

    private fun createMockFontBytes(includeGlyf: Boolean): ByteArray {
        val bos = ByteArrayOutputStream()

        val numTables = if (includeGlyf) 9 else 4

        // 1. SFNT Offset Table (12 bytes)
        writeUInt32Bytes(bos, 0x00010000L) // sfntVersion
        writeUInt16Bytes(bos, numTables) // numTables
        writeUInt16Bytes(bos, 64) // searchRange
        writeUInt16Bytes(bos, 2) // entrySelector
        writeUInt16Bytes(bos, 8) // rangeShift

        // Dummy table content definitions
        val headData = ByteArray(54)
        writeUInt32(headData, 12, 0x5F0F3CF5L) // magicNumber
        writeUInt16(headData, 44, 0) // macStyle: Regular
        writeUInt16(headData, 50, 0) // indexToLocFormat: short format

        val os2Data = ByteArray(64)
        writeUInt16(os2Data, 4, 400) // usWeightClass: Regular
        writeUInt16(os2Data, 62, 64) // fsSelection: REGULAR

        val postData = ByteArray(32)
        writeUInt32(postData, 0, 0x00020000L) // format Type 2.0
        writeUInt32(postData, 4, 0L) // italicAngle: 0.0

        // name table construction
        val nameDataStream = ByteArrayOutputStream()
        writeUInt16Bytes(nameDataStream, 0) // format
        writeUInt16Bytes(nameDataStream, 2) // count
        writeUInt16Bytes(nameDataStream, 6 + 2 * 12) // stringOffset

        val str1 = "Regular".toByteArray(StandardCharsets.UTF_16BE)
        val str2 = "MockFont Regular".toByteArray(StandardCharsets.UTF_16BE)

        // Record 1: Subfamily (nameID = 2)
        writeUInt16Bytes(nameDataStream, 3) // platformID: Windows
        writeUInt16Bytes(nameDataStream, 1) // encodingID: Unicode BMP
        writeUInt16Bytes(nameDataStream, 1033) // languageID: English
        writeUInt16Bytes(nameDataStream, 2) // nameID: Subfamily
        writeUInt16Bytes(nameDataStream, str1.size)
        writeUInt16Bytes(nameDataStream, 0) // offset

        // Record 2: Full Name (nameID = 4)
        writeUInt16Bytes(nameDataStream, 3) // platformID: Windows
        writeUInt16Bytes(nameDataStream, 1) // encodingID: Unicode BMP
        writeUInt16Bytes(nameDataStream, 1033) // languageID: English
        writeUInt16Bytes(nameDataStream, 4) // nameID: Full Name
        writeUInt16Bytes(nameDataStream, str2.size)
        writeUInt16Bytes(nameDataStream, str1.size) // offset

        nameDataStream.write(str1)
        nameDataStream.write(str2)
        val nameData = nameDataStream.toByteArray()

        // Optional Tables for Glyphs
        val maxpData = ByteArray(32)
        writeUInt16(maxpData, 4, 1) // numGlyphs = 1

        val locaData = ByteArray(4)
        writeUInt16(locaData, 0, 0) // offset 0
        writeUInt16(locaData, 2, 17) // offset 34 bytes (divided by 2 is 17)

        // glyfData contains one simple glyph:
        // numberOfContours = 1, xMin=0, yMin=0, xMax=100, yMax=100
        val glyfStream = ByteArrayOutputStream()
        writeShortBytes(glyfStream, 1) // numberOfContours
        writeShortBytes(glyfStream, 0) // xMin
        writeShortBytes(glyfStream, 0) // yMin
        writeShortBytes(glyfStream, 100) // xMax
        writeShortBytes(glyfStream, 100) // yMax
        writeUInt16Bytes(glyfStream, 3) // endPtsOfContours[0] = 3 (4 points total)
        writeUInt16Bytes(glyfStream, 0) // instructionLength = 0
        // Flags: 4 points. Let's make flag 0x01 (On Curve), no repeats, no short/same.
        // This means each delta is a 16-bit signed short.
        glyfStream.write(0x01)
        glyfStream.write(0x01)
        glyfStream.write(0x01)
        glyfStream.write(0x01)
        // X coordinates relative: 10, 20, 30, 40 -> absolute: 10, 30, 60, 100
        writeShortBytes(glyfStream, 10)
        writeShortBytes(glyfStream, 20)
        writeShortBytes(glyfStream, 30)
        writeShortBytes(glyfStream, 40)
        // Y coordinates relative: 10, 20, 30, 40 -> absolute: 10, 30, 60, 100
        writeShortBytes(glyfStream, 10)
        writeShortBytes(glyfStream, 20)
        writeShortBytes(glyfStream, 30)
        writeShortBytes(glyfStream, 40)
        val glyfData = glyfStream.toByteArray()

        val hheaData = ByteArray(36)
        writeUInt16(hheaData, 34, 1) // numberOfHMetrics = 1

        val hmtxData = ByteArray(4)
        writeUInt16(hmtxData, 0, 1000) // advanceWidth = 1000
        writeUInt16(hmtxData, 2, 50) // lsb = 50

        // 2. Write Table Records and Table Data sequential alignment
        val tables = LinkedHashMap<String, ByteArray>()
        tables["OS/2"] = os2Data
        tables["head"] = headData
        tables["name"] = nameData
        tables["post"] = postData
        if (includeGlyf) {
            tables["maxp"] = maxpData
            tables["loca"] = locaData
            tables["glyf"] = glyfData
            tables["hhea"] = hheaData
            tables["hmtx"] = hmtxData
        }

        var offset = 12 + numTables * 16
        val recordStream = ByteArrayOutputStream()
        val dataStream = ByteArrayOutputStream()

        for ((tag, data) in tables) {
            val padding = (offset + 3) / 4 * 4 - offset
            if (padding > 0) {
                dataStream.write(ByteArray(padding))
                offset += padding
            }
            val recordChecksum = computeChecksum(data)
            recordStream.write(tag.toByteArray(StandardCharsets.US_ASCII))
            writeUInt32Bytes(recordStream, recordChecksum)
            writeUInt32Bytes(recordStream, offset.toLong())
            writeUInt32Bytes(recordStream, data.size.toLong())

            dataStream.write(data)
            offset += data.size
        }

        bos.write(recordStream.toByteArray())
        bos.write(dataStream.toByteArray())

        return bos.toByteArray()
    }

    private fun verifyFontAttributes(resultBytes: ByteArray, style: FontConverter.Style) {
        val numTables = readUInt16(resultBytes, 4)
        var index = 12
        for (i in 0 until numTables) {
            val tag = String(resultBytes, index, 4, StandardCharsets.US_ASCII)
            val offset = readUInt32(resultBytes, index + 8).toInt()
            val length = readUInt32(resultBytes, index + 12).toInt()
            val data = resultBytes.copyOfRange(offset, offset + length)

            when (tag) {
                "head" -> {
                    val macStyle = readUInt16(data, 44)
                    when (style) {
                        FontConverter.Style.ITALIC -> assertEquals(0x02, macStyle and 0x02)
                        FontConverter.Style.BOLD -> assertEquals(0x01, macStyle and 0x01)
                        FontConverter.Style.BOLD_ITALIC -> {
                            assertEquals(0x01, macStyle and 0x01)
                            assertEquals(0x02, macStyle and 0x02)
                        }
                    }
                }
                "OS/2" -> {
                    val fsSelection = readUInt16(data, 62)
                    val weightClass = readUInt16(data, 4)
                    when (style) {
                        FontConverter.Style.ITALIC -> {
                            assertEquals(0x0001, fsSelection and 0x0001)
                            assertEquals(0, fsSelection and 0x0020)
                            assertEquals(0, fsSelection and 0x0040)
                        }
                        FontConverter.Style.BOLD -> {
                            assertEquals(0x0020, fsSelection and 0x0020)
                            assertEquals(0, fsSelection and 0x0001)
                            assertEquals(0, fsSelection and 0x0040)
                            assertEquals(700, weightClass)
                        }
                        FontConverter.Style.BOLD_ITALIC -> {
                            assertEquals(0x0020, fsSelection and 0x0020)
                            assertEquals(0x0001, fsSelection and 0x0001)
                            assertEquals(0, fsSelection and 0x0040)
                            assertEquals(700, weightClass)
                        }
                    }
                }
                "post" -> {
                    val italicAngle = readUInt32(data, 4)
                    when (style) {
                        FontConverter.Style.ITALIC, FontConverter.Style.BOLD_ITALIC -> {
                            assertEquals(0xFFF40000L, italicAngle)
                        }
                        else -> {
                            assertEquals(0L, italicAngle)
                        }
                    }
                }
                "name" -> {
                    val count = readUInt16(data, 2)
                    val stringOffset = readUInt16(data, 4)
                    for (j in 0 until count) {
                        val recordIndex = 6 + j * 12
                        val nameID = readUInt16(data, recordIndex + 6)
                        val len = readUInt16(data, recordIndex + 8)
                        val off = readUInt16(data, recordIndex + 10)
                        val strBytes = data.copyOfRange(stringOffset + off, stringOffset + off + len)
                        val str = String(strBytes, StandardCharsets.UTF_16BE)

                        when (nameID) {
                            2 -> {
                                when (style) {
                                    FontConverter.Style.ITALIC -> assertEquals("Italic", str)
                                    FontConverter.Style.BOLD -> assertEquals("Bold", str)
                                    FontConverter.Style.BOLD_ITALIC -> assertEquals("Bold Italic", str)
                                }
                            }
                            4 -> {
                                when (style) {
                                    FontConverter.Style.ITALIC -> assertEquals("MockFont Italic", str)
                                    FontConverter.Style.BOLD -> assertEquals("MockFont Bold", str)
                                    FontConverter.Style.BOLD_ITALIC -> assertEquals("MockFont Bold Italic", str)
                                }
                            }
                        }
                    }
                }
            }
            index += 16
        }
    }

    private fun computeChecksum(data: ByteArray): Long {
        var sum = 0L
        val len = (data.size + 3) / 4 * 4
        for (i in 0 until len step 4) {
            var value = 0L
            for (j in 0 until 4) {
                val byteVal = if (i + j < data.size) data[i + j].toInt() and 0xFF else 0
                value = (value shl 8) or byteVal.toLong()
            }
            sum = (sum + value) and 0xFFFFFFFFL
        }
        return sum
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        val b1 = data[offset].toInt() and 0xFF
        val b2 = data[offset + 1].toInt() and 0xFF
        return (b1 shl 8) or b2
    }

    private fun readInt16(data: ByteArray, offset: Int): Int {
        val val16 = readUInt16(data, offset)
        return if (val16 >= 32768) val16 - 65536 else val16
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        val b1 = data[offset].toLong() and 0xFF
        val b2 = data[offset + 1].toLong() and 0xFF
        val b3 = data[offset + 2].toLong() and 0xFF
        val b4 = data[offset + 3].toLong() and 0xFF
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeUInt16Bytes(stream: ByteArrayOutputStream, value: Int) {
        stream.write((value ushr 8) and 0xFF)
        stream.write(value and 0xFF)
    }

    private fun writeShortBytes(stream: ByteArrayOutputStream, value: Int) {
        stream.write((value ushr 8) and 0xFF)
        stream.write(value and 0xFF)
    }

    private fun writeUInt32Bytes(stream: ByteArrayOutputStream, value: Long) {
        stream.write(((value ushr 24) and 0xFF).toInt())
        stream.write(((value ushr 16) and 0xFF).toInt())
        stream.write(((value ushr 8) and 0xFF).toInt())
        stream.write((value and 0xFF).toInt())
    }
}
