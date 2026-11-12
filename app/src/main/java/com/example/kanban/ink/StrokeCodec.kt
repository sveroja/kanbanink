package com.example.kanban.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Kompaktes Binärformat für die Striche einer Karte:
 * pro Strich Brush-Metadaten + die rohen Eingabepunkte (StrokeInputBatch).
 * So bleibt die Schrift verlustfrei, skalierbar und radierbar.
 */
object StrokeCodec {
    private const val FORMAT_VERSION = 1

    private const val FAMILY_PRESSURE_PEN = 0
    private const val FAMILY_MARKER = 1
    private const val FAMILY_HIGHLIGHTER = 2

    private fun familyId(family: BrushFamily): Int = when (family) {
        StockBrushes.marker() -> FAMILY_MARKER
        StockBrushes.highlighter() -> FAMILY_HIGHLIGHTER
        else -> FAMILY_PRESSURE_PEN
    }

    private fun familyFor(id: Int): BrushFamily = when (id) {
        FAMILY_MARKER -> StockBrushes.marker()
        FAMILY_HIGHLIGHTER -> StockBrushes.highlighter()
        else -> StockBrushes.pressurePen()
    }

    fun encode(strokes: List<Stroke>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeInt(strokes.size)
            for (stroke in strokes) {
                val brush = stroke.brush
                out.writeInt(familyId(brush.family))
                out.writeFloat(brush.size)
                out.writeFloat(brush.epsilon)
                out.writeLong(brush.colorLong)
                val inputs = ByteArrayOutputStream().also { stroke.inputs.encode(it) }.toByteArray()
                out.writeInt(inputs.size)
                out.write(inputs)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): List<Stroke> {
        if (data.isEmpty()) return emptyList()
        return try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readInt() != FORMAT_VERSION) return emptyList()
                val count = input.readInt()
                buildList(count) {
                    repeat(count) {
                        val family = familyFor(input.readInt())
                        val size = input.readFloat()
                        val epsilon = input.readFloat()
                        val color = input.readLong()
                        val inputBytes = ByteArray(input.readInt()).also { input.readFully(it) }
                        val batch = ByteArrayInputStream(inputBytes).use { StrokeInputBatch.decode(it) }
                        val brush = Brush.createWithColorLong(
                            family = family,
                            colorLong = color,
                            size = size,
                            epsilon = epsilon,
                        )
                        add(Stroke(brush = brush, inputs = batch))
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
