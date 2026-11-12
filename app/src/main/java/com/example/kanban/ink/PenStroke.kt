package com.example.kanban.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke

/** Ein Stiftpunkt in Weltkoordinaten (siehe [WORLD_WIDTH]); pressure in 0..1. */
class PenPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timeMillis: Long,
)

/**
 * Baut aus rohen Stiftpunkten (z. B. vom Onyx-SDK) einen Jetpack-Ink-[Stroke].
 * So bleiben Speicherformat, Vorschau und Radierer für beide Eingabewege gleich.
 */
fun strokeFromPenPoints(points: List<PenPoint>, brush: Brush): Stroke? {
    if (points.isEmpty()) return null
    val batch = MutableStrokeInputBatch()
    val startTime = points.first().timeMillis
    var lastElapsed = 0L
    for (point in points) {
        val elapsed = maxOf(lastElapsed, point.timeMillis - startTime)
        try {
            batch.add(
                type = InputToolType.STYLUS,
                x = point.x,
                y = point.y,
                elapsedTimeMillis = elapsed,
                pressure = point.pressure.coerceIn(0f, 1f),
            )
            lastElapsed = elapsed
        } catch (_: IllegalArgumentException) {
            // Ungültiger/doppelter Punkt – überspringen.
        }
    }
    if (batch.size == 0) return null
    return Stroke(brush = brush, inputs = batch)
}
