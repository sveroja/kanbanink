package com.example.kanban.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.graphics.withSave
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/**
 * Alle Striche werden in einem festen "Welt"-Koordinatensystem gespeichert
 * (unabhängig von Bildschirmgröße/Ausrichtung). Beim Zeichnen wird nur skaliert.
 */
const val WORLD_WIDTH = 1000f
const val WORLD_HEIGHT = 500f
const val CARD_ASPECT_RATIO = WORLD_WIDTH / WORLD_HEIGHT

private val highlighterFamily by lazy { StockBrushes.highlighter() }

val Stroke.isHighlighter: Boolean get() = brush.family == highlighterFamily

/**
 * Zeichnet Weltkoordinaten-Striche skaliert auf einen Android-Canvas.
 * Textmarker werden immer zuerst gezeichnet, damit die Schrift darüber
 * schwarz und scharf bleibt – egal in welcher Reihenfolge geschrieben wurde.
 */
fun android.graphics.Canvas.drawInkStrokes(
    strokes: List<Stroke>,
    renderer: CanvasStrokeRenderer,
    scale: Float,
) {
    val transform = android.graphics.Matrix().apply { setScale(scale, scale) }
    for (highlighterPass in listOf(true, false)) {
        for (stroke in strokes) {
            if (stroke.isHighlighter != highlighterPass) continue
            withSave {
                scale(scale, scale)
                renderer.draw(canvas = this, stroke = stroke, strokeToScreenTransform = transform)
            }
        }
    }
}

private fun DrawScope.drawStrokesScaled(
    strokes: List<Stroke>,
    renderer: CanvasStrokeRenderer,
    scale: Float,
) = drawContext.canvas.nativeCanvas.drawInkStrokes(strokes, renderer, scale)

/** Nur-Lese-Darstellung der Handschrift einer Karte, skaliert auf die verfügbare Breite. */
@Composable
fun StrokeThumbnail(
    strokes: List<Stroke>,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(CARD_ASPECT_RATIO)) {
        drawStrokesScaled(strokes, renderer, size.width / WORLD_WIDTH)
    }
}

/**
 * Schreibfläche: Dry Ink (fertige Striche) + Wet Ink (InProgressStrokes) bzw.
 * Radierer-Eingabe. Eingaben werden in Weltkoordinaten umgerechnet.
 */
@Composable
fun InkEditorSurface(
    strokes: List<Stroke>,
    brush: Brush,
    isEraserMode: Boolean,
    onStrokesFinished: (List<Stroke>) -> Unit,
    onEraseStart: () -> Unit,
    onErase: (worldX: Float, worldY: Float) -> Unit,
    onEraseEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    var widthPx by remember { mutableFloatStateOf(0f) }

    // Puffert gerade fertig gewordene Striche für genau einen Frame, bis der
    // ViewModel-State nachzieht – verhindert Flackern beim Wet→Dry-Übergang.
    var pendingStrokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    SideEffect {
        if (pendingStrokes.isNotEmpty()) {
            pendingStrokes = pendingStrokes.filter { pending -> strokes.none { it === pending } }
        }
    }
    val strokesToDraw = remember(strokes, pendingStrokes) {
        if (pendingStrokes.isEmpty()) strokes
        else strokes + pendingStrokes.filter { pending -> strokes.none { it === pending } }
    }

    val currentOnErase by rememberUpdatedState(onErase)
    val currentOnEraseStart by rememberUpdatedState(onEraseStart)
    val currentOnEraseEnd by rememberUpdatedState(onEraseEnd)

    Box(
        modifier = modifier
            .aspectRatio(CARD_ASPECT_RATIO)
            .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        if (widthPx <= 0f) return@Box
        val screenScale = widthPx / WORLD_WIDTH
        val pointerToWorld = remember(screenScale) {
            Matrix().apply { scale(1f / screenScale, 1f / screenScale) }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawStrokesScaled(strokesToDraw, renderer, screenScale)
        }

        if (isEraserMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(screenScale) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            currentOnEraseStart()
                            currentOnErase(down.position.x / screenScale, down.position.y / screenScale)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                currentOnErase(change.position.x / screenScale, change.position.y / screenScale)
                                change.consume()
                            }
                            currentOnEraseEnd()
                        }
                    },
            )
        } else {
            InProgressStrokes(
                defaultBrush = brush,
                pointerEventToWorldTransform = pointerToWorld,
                onStrokesFinished = { newStrokes ->
                    pendingStrokes = pendingStrokes + newStrokes
                    onStrokesFinished(newStrokes)
                },
            )
        }
    }
}
