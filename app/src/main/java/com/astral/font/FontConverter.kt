package com.astral.font

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object FontConverter {

    enum class Style {
        ITALIC, BOLD, BOLD_ITALIC
    }

    private class TableRecord(
        val tag: String,
        var checksum: Long,
        var offset: Long,
        var length: Long,
        var data: ByteArray
    )

    fun convertFont(inputStream: InputStream, outputStream: OutputStream, targetStyle: Style) {
        val inputBytes = inputStream.readBytes()
        if (inputBytes.size < 12) {
            throw IllegalArgumentException("Invalid font file: file is too small")
        }

        // 1. Read SFNT Offset Table
        val sfntVersion = readUInt32(inputBytes, 0)
        val numTables = readUInt16(inputBytes, 4)
        val searchRange = readUInt16(inputBytes, 6)
        val entrySelector = readUInt16(inputBytes, 8)
        val rangeShift = readUInt16(inputBytes, 10)

        if (inputBytes.size < 12 + numTables * 16) {
            throw IllegalArgumentException("Invalid font file: table directory is truncated")
        }

        // 2. Read Table Records
        val tableRecords = ArrayList<TableRecord>()
        var index = 12
        for (i in 0 until numTables) {
            val tag = String(inputBytes, index, 4, StandardCharsets.US_ASCII)
            val checksum = readUInt32(inputBytes, index + 4)
            val offset = readUInt32(inputBytes, index + 8)
            val length = readUInt32(inputBytes, index + 12)

            if (offset + length > inputBytes.size) {
                throw IllegalArgumentException("Invalid font file: table data is out of bounds for table $tag")
            }

            val tableData = inputBytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            tableRecords.add(TableRecord(tag, checksum, offset, length, tableData))
            index += 16
        }

        // 3. Modify tables
        modifyHeadTable(tableRecords, targetStyle)
        modifyOS2Table(tableRecords, targetStyle)
        modifyPostTable(tableRecords, targetStyle)
        modifyNameTable(tableRecords, targetStyle)

        // 4. Rebuild Font File
        rebuildFont(sfntVersion, searchRange, entrySelector, rangeShift, tableRecords, outputStream)
    }

    private fun modifyHeadTable(tableRecords: List<TableRecord>, style: Style) {
        val headRecord = tableRecords.find { it.tag == "head" } ?: return
        val data = headRecord.data
        if (data.size < 46) return

        // macStyle is at offset 44 (2 bytes)
        var macStyle = readUInt16(data, 44)
        when (style) {
            Style.ITALIC -> {
                macStyle = macStyle or 0x02 // bit 1: Italic
            }
            Style.BOLD -> {
                macStyle = macStyle or 0x01 // bit 0: Bold
            }
            Style.BOLD_ITALIC -> {
                macStyle = macStyle or 0x01 or 0x02 // bit 0: Bold, bit 1: Italic
            }
        }
        writeUInt16(data, 44, macStyle)

        // Clear checkSumAdjustment at offset 8 (4 bytes) so we can recalculate it later
        writeUInt32(data, 8, 0L)
    }

    private fun modifyOS2Table(tableRecords: List<TableRecord>, style: Style) {
        val os2Record = tableRecords.find { it.tag == "OS/2" } ?: return
        val data = os2Record.data
        if (data.size < 64) return

        // usWeightClass is at offset 4 (2 bytes)
        if (style == Style.BOLD || style == Style.BOLD_ITALIC) {
            writeUInt16(data, 4, 700) // Bold weight class is 700
        }

        // fsSelection is at offset 62 (2 bytes)
        var fsSelection = readUInt16(data, 62)
        when (style) {
            Style.ITALIC -> {
                fsSelection = fsSelection or 0x0001 // bit 0: ITALIC
                fsSelection = fsSelection and 0x0020.inv() // clear BOLD
                fsSelection = fsSelection and 0x0040.inv() // clear REGULAR
                fsSelection = fsSelection or 0x0200 // bit 9: OBLIQUE
            }
            Style.BOLD -> {
                fsSelection = fsSelection or 0x0020 // bit 5: BOLD
                fsSelection = fsSelection and 0x0001.inv() // clear ITALIC
                fsSelection = fsSelection and 0x0040.inv() // clear REGULAR
            }
            Style.BOLD_ITALIC -> {
                fsSelection = fsSelection or 0x0020 or 0x0001 // bit 5: BOLD, bit 0: ITALIC
                fsSelection = fsSelection and 0x0040.inv() // clear REGULAR
                fsSelection = fsSelection or 0x0200 // bit 9: OBLIQUE
            }
        }
        writeUInt16(data, 62, fsSelection)
    }

    private fun modifyPostTable(tableRecords: List<TableRecord>, style: Style) {
        val postRecord = tableRecords.find { it.tag == "post" } ?: return
        val data = postRecord.data
        if (data.size < 8) return

        // italicAngle is at offset 4 (4 bytes, Fixed 16.16)
        val italicAngle: Long = when (style) {
            Style.ITALIC, Style.BOLD_ITALIC -> 0xFFF40000L // -12.0 degrees (big endian hex format representation of -12 * 65536)
            else -> 0L
        }
        writeUInt32(data, 4, italicAngle)
    }

    private fun modifyNameTable(tableRecords: ArrayList<TableRecord>, style: Style) {
        val nameRecord = tableRecords.find { it.tag == "name" } ?: return
        val data = nameRecord.data
        if (data.size < 6) return

        val format = readUInt16(data, 0)
        val count = readUInt16(data, 2)
        val stringOffset = readUInt16(data, 4)

        if (data.size < 6 + count * 12) return

        val styleSuffix = when (style) {
            Style.ITALIC -> "Italic"
            Style.BOLD -> "Bold"
            Style.BOLD_ITALIC -> "Bold Italic"
        }

        val psStyleSuffix = when (style) {
            Style.ITALIC -> "Italic"
            Style.BOLD -> "Bold"
            Style.BOLD_ITALIC -> "BoldItalic"
        }

        val newRecordsBytes = ByteArrayOutputStream()
        val newStringsBytes = ByteArrayOutputStream()

        var currentStringOffset = 0

        for (i in 0 until count) {
            val recordIndex = 6 + i * 12
            val platformID = readUInt16(data, recordIndex)
            val encodingID = readUInt16(data, recordIndex + 2)
            val languageID = readUInt16(data, recordIndex + 4)
            val nameID = readUInt16(data, recordIndex + 6)
            val length = readUInt16(data, recordIndex + 8)
            val offset = readUInt16(data, recordIndex + 10)

            val startIdx = stringOffset + offset
            if (startIdx + length > data.size) continue

            val originalBytes = data.copyOfRange(startIdx, startIdx + length)
            val isUtf16 = (platformID == 3 && encodingID == 1) || platformID == 0

            val originalString = if (isUtf16) {
                String(originalBytes, StandardCharsets.UTF_16BE)
            } else {
                String(originalBytes, StandardCharsets.US_ASCII)
            }

            var newString = originalString

            when (nameID) {
                2 -> {
                    // Font Subfamily Name
                    newString = styleSuffix
                }
                3 -> {
                    // Unique Font Identifier
                    if (!newString.contains(styleSuffix, ignoreCase = true)) {
                        newString = if (newString.endsWith("Regular", ignoreCase = true)) {
                            newString.substring(0, newString.length - 7) + styleSuffix
                        } else {
                            "$newString $styleSuffix"
                        }
                    }
                }
                4 -> {
                    // Full Font Name
                    if (!newString.contains(styleSuffix, ignoreCase = true)) {
                        newString = if (newString.endsWith("Regular", ignoreCase = true)) {
                            newString.substring(0, newString.length - 7) + styleSuffix
                        } else {
                            "$newString $styleSuffix"
                        }
                    }
                }
                6 -> {
                    // PostScript Name (no spaces)
                    if (!newString.contains(psStyleSuffix, ignoreCase = true)) {
                        newString = if (newString.endsWith("-Regular", ignoreCase = true)) {
                            newString.substring(0, newString.length - 8) + "-" + psStyleSuffix
                        } else if (newString.endsWith("Regular", ignoreCase = true)) {
                            newString.substring(0, newString.length - 7) + psStyleSuffix
                        } else {
                            "$newString-$psStyleSuffix"
                        }
                    }
                    newString = newString.replace(" ", "")
                }
                17 -> {
                    // Preferred Subfamily Name
                    newString = styleSuffix
                }
            }

            val newBytes = if (isUtf16) {
                newString.toByteArray(StandardCharsets.UTF_16BE)
            } else {
                newString.toByteArray(StandardCharsets.US_ASCII)
            }

            // Write updated NameRecord
            writeUInt16Bytes(newRecordsBytes, platformID)
            writeUInt16Bytes(newRecordsBytes, encodingID)
            writeUInt16Bytes(newRecordsBytes, languageID)
            writeUInt16Bytes(newRecordsBytes, nameID)
            writeUInt16Bytes(newRecordsBytes, newBytes.size)
            writeUInt16Bytes(newRecordsBytes, currentStringOffset)

            newStringsBytes.write(newBytes)
            currentStringOffset += newBytes.size
        }

        // Reconstruct the name table
        val newNameTableBytes = ByteArrayOutputStream()
        writeUInt16Bytes(newNameTableBytes, format)
        writeUInt16Bytes(newNameTableBytes, count)
        writeUInt16Bytes(newNameTableBytes, 6 + count * 12) // String offset starts after header + records

        newNameTableBytes.write(newRecordsBytes.toByteArray())
        newNameTableBytes.write(newStringsBytes.toByteArray())

        nameRecord.data = newNameTableBytes.toByteArray()
        nameRecord.length = nameRecord.data.size.toLong()
    }

    private fun rebuildFont(
        sfntVersion: Long,
        searchRange: Int,
        entrySelector: Int,
        rangeShift: Int,
        tableRecords: ArrayList<TableRecord>,
        outputStream: OutputStream
    ) {
        val numTables = tableRecords.size

        // Calculate starting offset for tables.
        // Offset table (12 bytes) + Table records (16 bytes each)
        var currentOffset = 12L + numTables * 16L

        // Update offset and length for each table, recalculating checksum
        for (record in tableRecords) {
            // Table data must align to a 4-byte boundary
            currentOffset = (currentOffset + 3) / 4 * 4
            record.offset = currentOffset
            record.length = record.data.size.toLong()
            record.checksum = computeChecksum(record.data)
            currentOffset += record.length
        }

        // Create the font file buffer
        val fontBuffer = ByteArrayOutputStream()

        // 1. Write SFNT Offset Table
        writeUInt32Bytes(fontBuffer, sfntVersion)
        writeUInt16Bytes(fontBuffer, numTables)
        writeUInt16Bytes(fontBuffer, searchRange)
        writeUInt16Bytes(fontBuffer, entrySelector)
        writeUInt16Bytes(fontBuffer, rangeShift)

        // 2. Write Table Records
        for (record in tableRecords) {
            val tagBytes = record.tag.toByteArray(StandardCharsets.US_ASCII)
            fontBuffer.write(tagBytes)
            writeUInt32Bytes(fontBuffer, record.checksum)
            writeUInt32Bytes(fontBuffer, record.offset)
            writeUInt32Bytes(fontBuffer, record.length)
        }

        // 3. Write Table Data
        for (record in tableRecords) {
            val paddingNeeded = (record.offset - fontBuffer.size()).toInt()
            if (paddingNeeded > 0) {
                fontBuffer.write(ByteArray(paddingNeeded))
            }
            fontBuffer.write(record.data)
        }

        // Pad the entire file to a multiple of 4
        val finalPaddingNeeded = ((fontBuffer.size() + 3) / 4 * 4) - fontBuffer.size()
        if (finalPaddingNeeded > 0) {
            fontBuffer.write(ByteArray(finalPaddingNeeded))
        }

        val completedBytes = fontBuffer.toByteArray()

        // 4. Calculate entire file checksum adjustment and write to the 'head' table
        val headRecord = tableRecords.find { it.tag == "head" }
        if (headRecord != null) {
            val headTableOffset = headRecord.offset.toInt()
            // Clear checkSumAdjustment field at offset 8 of the head table
            writeUInt32(completedBytes, headTableOffset + 8, 0L)

            // Calculate entire file checksum
            val entireFileChecksum = computeChecksum(completedBytes)
            val checkSumAdjustment = (0xB1B0AFBAL - entireFileChecksum) and 0xFFFFFFFFL

            // Write it back
            writeUInt32(completedBytes, headTableOffset + 8, checkSumAdjustment)
        }

        outputStream.write(completedBytes)
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

    // Helper utilities for big-endian read/write
    private fun readUInt16(data: ByteArray, offset: Int): Int {
        val b1 = data[offset].toInt() and 0xFF
        val b2 = data[offset + 1].toInt() and 0xFF
        return (b1 shl 8) or b2
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

    private fun writeUInt32Bytes(stream: ByteArrayOutputStream, value: Long) {
        stream.write(((value ushr 24) and 0xFF).toInt())
        stream.write(((value ushr 16) and 0xFF).toInt())
        stream.write(((value ushr 8) and 0xFF).toInt())
        stream.write((value and 0xFF).toInt())
    }
}
