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

        // 3. Modify metadata tables
        modifyHeadTable(tableRecords, targetStyle)
        modifyOS2Table(tableRecords, targetStyle)
        modifyPostTable(tableRecords, targetStyle)
        modifyNameTable(tableRecords, targetStyle)

        // 4. Modify glyphs and horizontal metrics if 'glyf' table is present (TrueType outlines)
        val hasGlyfTable = tableRecords.any { it.tag == "glyf" }
        if (hasGlyfTable) {
            modifyGlyphsAndMetrics(tableRecords, targetStyle)
        }

        // 5. Rebuild Font File
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

    private fun modifyGlyphsAndMetrics(tableRecords: ArrayList<TableRecord>, style: Style) {
        val maxpRecord = tableRecords.find { it.tag == "maxp" } ?: return
        val numGlyphs = readUInt16(maxpRecord.data, 4)

        val headRecord = tableRecords.find { it.tag == "head" } ?: return
        val indexToLocFormat = readUInt16(headRecord.data, 50)

        val locaRecord = tableRecords.find { it.tag == "loca" } ?: return
        val glyfRecord = tableRecords.find { it.tag == "glyf" } ?: return

        // 1. Read Glyf Offsets from loca
        val offsets = LongArray(numGlyphs + 1)
        val locaData = locaRecord.data
        if (indexToLocFormat == 0) {
            for (i in 0..numGlyphs) {
                offsets[i] = readUInt16(locaData, i * 2).toLong() * 2
            }
        } else {
            for (i in 0..numGlyphs) {
                offsets[i] = readUInt32(locaData, i * 4)
            }
        }

        // 2. Parse and Modify Glyphs
        val newGlyphsData = ArrayList<ByteArray>()
        for (i in 0 until numGlyphs) {
            val length = (offsets[i + 1] - offsets[i]).toInt()
            if (length <= 0) {
                newGlyphsData.add(ByteArray(0))
                continue
            }

            val glyphBytes = glyfRecord.data.copyOfRange(offsets[i].toInt(), offsets[i + 1].toInt())
            if (glyphBytes.size < 10) {
                newGlyphsData.add(glyphBytes)
                continue
            }

            val numberOfContours = readInt16(glyphBytes, 0)
            val xMin = readInt16(glyphBytes, 2)
            val yMin = readInt16(glyphBytes, 4)
            val xMax = readInt16(glyphBytes, 6)
            val yMax = readInt16(glyphBytes, 8)

            if (numberOfContours >= 0) {
                // Simple Glyph
                val endPtsOfContours = IntArray(numberOfContours)
                var ptr = 10
                for (c in 0 until numberOfContours) {
                    if (ptr + 2 > glyphBytes.size) break
                    endPtsOfContours[c] = readUInt16(glyphBytes, ptr)
                    ptr += 2
                }

                val numPoints = if (numberOfContours > 0 && endPtsOfContours[numberOfContours - 1] < 10000) {
                    endPtsOfContours[numberOfContours - 1] + 1
                } else {
                    0
                }

                if (numPoints <= 0 || ptr + 2 > glyphBytes.size) {
                    newGlyphsData.add(glyphBytes)
                    continue
                }

                val instructionLength = readUInt16(glyphBytes, ptr)
                ptr += 2

                val instructionsStart = ptr
                ptr += instructionLength

                // Parse flags
                val flags = IntArray(numPoints)
                var pCount = 0
                while (pCount < numPoints) {
                    if (ptr >= glyphBytes.size) break
                    val flag = glyphBytes[ptr].toInt() and 0xFF
                    ptr++
                    flags[pCount] = flag
                    pCount++
                    if ((flag and 0x08) != 0) { // Repeat flag
                        if (ptr >= glyphBytes.size) break
                        val repeatCount = glyphBytes[ptr].toInt() and 0xFF
                        ptr++
                        for (r in 0 until repeatCount) {
                            if (pCount >= numPoints) break
                            flags[pCount] = flag
                            pCount++
                        }
                    }
                }

                // Parse X coordinates
                val xCoordinates = IntArray(numPoints)
                var currentX = 0
                for (p in 0 until numPoints) {
                    val flag = flags[p]
                    val xShort = (flag and 0x02) != 0
                    val xSameOrSign = (flag and 0x10) != 0

                    val deltaX: Int
                    if (xShort) {
                        if (ptr >= glyphBytes.size) break
                        val uVal = glyphBytes[ptr].toInt() and 0xFF
                        ptr++
                        deltaX = if (xSameOrSign) uVal else -uVal
                    } else {
                        if (xSameOrSign) {
                            deltaX = 0
                        } else {
                            if (ptr + 1 >= glyphBytes.size) break
                            deltaX = readInt16(glyphBytes, ptr)
                            ptr += 2
                        }
                    }
                    currentX += deltaX
                    xCoordinates[p] = currentX
                }

                // Parse Y coordinates
                val yCoordinates = IntArray(numPoints)
                var currentY = 0
                for (p in 0 until numPoints) {
                    val flag = flags[p]
                    val yShort = (flag and 0x04) != 0
                    val ySameOrSign = (flag and 0x20) != 0

                    val deltaY: Int
                    if (yShort) {
                        if (ptr >= glyphBytes.size) break
                        val uVal = glyphBytes[ptr].toInt() and 0xFF
                        ptr++
                        deltaY = if (ySameOrSign) uVal else -uVal
                    } else {
                        if (ySameOrSign) {
                            deltaY = 0
                        } else {
                            if (ptr + 1 >= glyphBytes.size) break
                            deltaY = readInt16(glyphBytes, ptr)
                            ptr += 2
                        }
                    }
                    currentY += deltaY
                    yCoordinates[p] = currentY
                }

                // Apply Outline Transformations: Bold (normal-vector expansion) first, then Italic (slanting)
                if (style == Style.BOLD || style == Style.BOLD_ITALIC) {
                    val unitsPerEm = readUInt16(headRecord.data, 18)
                    val scaleFactor = unitsPerEm.toDouble() / 1000.0
                    val strengthX = 35.0 * scaleFactor
                    val strengthY = 10.0 * scaleFactor

                    // Store coordinate shift values for each point
                    val shiftX = DoubleArray(numPoints)
                    val shiftY = DoubleArray(numPoints)

                    var startIdx = 0
                    for (c in 0 until numberOfContours) {
                        val endIdx = endPtsOfContours[c]
                        if (endIdx < startIdx || endIdx >= numPoints) break

                        for (ptIdx in startIdx..endIdx) {
                            val prevIdx = if (ptIdx == startIdx) endIdx else ptIdx - 1
                            val nextIdx = if (ptIdx == endIdx) startIdx else ptIdx + 1

                            val tx = xCoordinates[nextIdx] - xCoordinates[prevIdx]
                            val ty = yCoordinates[nextIdx] - yCoordinates[prevIdx]

                            val len = Math.hypot(tx.toDouble(), ty.toDouble())
                            if (len > 0.0) {
                                val nx = -ty / len
                                val ny = tx / len
                                shiftX[ptIdx] = nx * strengthX
                                shiftY[ptIdx] = ny * strengthY
                            }
                        }
                        startIdx = endIdx + 1
                    }

                    // Apply fatter strokes and a small horizontal scale (1.05) to give spacing
                    for (ptIdx in 0 until numPoints) {
                        val expandedX = xCoordinates[ptIdx] * 1.05 + shiftX[ptIdx]
                        val expandedY = yCoordinates[ptIdx] + shiftY[ptIdx]
                        xCoordinates[ptIdx] = Math.round(expandedX).toInt()
                        yCoordinates[ptIdx] = Math.round(expandedY).toInt()
                    }
                }

                if (style == Style.ITALIC || style == Style.BOLD_ITALIC) {
                    for (ptIdx in 0 until numPoints) {
                        val slantedX = xCoordinates[ptIdx] + yCoordinates[ptIdx] * 0.212
                        xCoordinates[ptIdx] = Math.round(slantedX).toInt()
                    }
                }

                // Recalculate Bounding Box
                var newXMin = 0
                var newYMin = 0
                var newXMax = 0
                var newYMax = 0
                if (numPoints > 0) {
                    newXMin = xCoordinates.minOrNull() ?: 0
                    newYMin = yCoordinates.minOrNull() ?: 0
                    newXMax = xCoordinates.maxOrNull() ?: 0
                    newYMax = yCoordinates.maxOrNull() ?: 0
                }

                // Serialize Simple Glyph
                val out = ByteArrayOutputStream()
                writeShortBytes(out, numberOfContours)
                writeShortBytes(out, newXMin)
                writeShortBytes(out, newYMin)
                writeShortBytes(out, newXMax)
                writeShortBytes(out, newYMax)

                for (c in 0 until numberOfContours) {
                    writeUInt16Bytes(out, endPtsOfContours[c])
                }

                writeUInt16Bytes(out, instructionLength)
                if (instructionLength > 0 && instructionsStart + instructionLength <= glyphBytes.size) {
                    out.write(glyphBytes, instructionsStart, instructionLength)
                }

                // Flag: 1 byte per point, keep only On Curve (bit 0).
                // This means coordinate deltas will always be 16-bit signed offsets (safest write format).
                for (p in 0 until numPoints) {
                    out.write(flags[p] and 0x01)
                }

                // Write X as signed 16-bit relative deltas
                var prevX = 0
                for (p in 0 until numPoints) {
                    val currX = xCoordinates[p]
                    writeShortBytes(out, currX - prevX)
                    prevX = currX
                }

                // Write Y as signed 16-bit relative deltas
                var prevY = 0
                for (p in 0 until numPoints) {
                    val currY = yCoordinates[p]
                    writeShortBytes(out, currY - prevY)
                    prevY = currY
                }

                newGlyphsData.add(out.toByteArray())
            } else {
                // Compound Glyph: We just scale/slant the bounding box
                val compBytes = glyphBytes.clone()
                val newXMin = when (style) {
                    Style.ITALIC -> xMin + yMin * 0.212
                    Style.BOLD -> xMin * 1.05 - 20
                    Style.BOLD_ITALIC -> (xMin * 1.05 - 20) + yMin * 0.212
                }
                val newXMax = when (style) {
                    Style.ITALIC -> xMax + yMax * 0.212
                    Style.BOLD -> xMax * 1.05 + 20
                    Style.BOLD_ITALIC -> (xMax * 1.05 + 20) + yMax * 0.212
                }
                writeShort(compBytes, 2, Math.round(newXMin).toInt())
                writeShort(compBytes, 6, Math.round(newXMax).toInt())
                newGlyphsData.add(compBytes)
            }
        }

        // 3. Update 'indexToLocFormat' in 'head' to 1 (Long format)
        writeUInt16(headRecord.data, 50, 1)

        // 4. Rebuild glyf and loca tables with 2-byte alignment padding for each glyph
        val newGlyfStream = ByteArrayOutputStream()
        val newOffsets = LongArray(numGlyphs + 1)
        for (idx in 0 until numGlyphs) {
            newOffsets[idx] = newGlyfStream.size().toLong()
            val glyphData = newGlyphsData[idx]
            newGlyfStream.write(glyphData)
            if (glyphData.size % 2 != 0) {
                newGlyfStream.write(0)
            }
        }
        newOffsets[numGlyphs] = newGlyfStream.size().toLong()

        glyfRecord.data = newGlyfStream.toByteArray()
        glyfRecord.length = glyfRecord.data.size.toLong()

        val newLocaStream = ByteArrayOutputStream()
        for (offset in newOffsets) {
            writeUInt32Bytes(newLocaStream, offset)
        }
        locaRecord.data = newLocaStream.toByteArray()
        locaRecord.length = locaRecord.data.size.toLong()

        // 5. Update hmtx metrics
        val hheaRecord = tableRecords.find { it.tag == "hhea" } ?: return
        val numberOfHMetrics = readUInt16(hheaRecord.data, 34)

        val hmtxRecord = tableRecords.find { it.tag == "hmtx" } ?: return
        val hmtxData = hmtxRecord.data

        val newHmtxStream = ByteArrayOutputStream()
        var hmtxPtr = 0

        for (idx in 0 until numberOfHMetrics) {
            if (hmtxPtr + 4 > hmtxData.size) break
            var advanceWidth = readUInt16(hmtxData, hmtxPtr)
            var lsb = readInt16(hmtxData, hmtxPtr + 2)

            when (style) {
                Style.ITALIC -> {
                    advanceWidth = Math.round(advanceWidth * 1.05).toInt()
                    lsb = Math.round(lsb * 1.05).toInt()
                }
                Style.BOLD, Style.BOLD_ITALIC -> {
                    advanceWidth = Math.round(advanceWidth * 1.08 + 35).toInt()
                    lsb = Math.round(lsb * 1.05 - 15).toInt()
                }
            }

            writeUInt16Bytes(newHmtxStream, advanceWidth)
            writeShortBytes(newHmtxStream, lsb)
            hmtxPtr += 4
        }

        val numLsbRemaining = numGlyphs - numberOfHMetrics
        for (idx in 0 until numLsbRemaining) {
            if (hmtxPtr + 2 > hmtxData.size) break
            var lsb = readInt16(hmtxData, hmtxPtr)

            when (style) {
                Style.ITALIC -> {
                    lsb = Math.round(lsb * 1.05).toInt()
                }
                Style.BOLD, Style.BOLD_ITALIC -> {
                    lsb = Math.round(lsb * 1.05 - 15).toInt()
                }
            }

            writeShortBytes(newHmtxStream, lsb)
            hmtxPtr += 2
        }

        hmtxRecord.data = newHmtxStream.toByteArray()
        hmtxRecord.length = hmtxRecord.data.size.toLong()
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

    private fun readInt16(data: ByteArray, offset: Int): Int {
        val val16 = readUInt16(data, offset)
        return if (val16 >= 32768) val16 - 65536 else val16
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

    private fun writeShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
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