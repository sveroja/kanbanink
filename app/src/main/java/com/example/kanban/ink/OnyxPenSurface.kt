package com.example.kanban.ink

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/** true auf Onyx-Boox-Geräten – dort wird das Onyx Pen SDK verwendet. */
val isOnyxDevice: Boolean = Build.MANUFACTURER.equals("ONYX", ignoreCase = true)

private const val TAG = "OnyxPenSurface"

/**
 * Schreibfläche mit dem Onyx Pen SDK: Der Stift wird vom E-Ink-Controller direkt
 * (nahezu verzögerungsfrei) gezeichnet; die App bekommt danach die Punkte geliefert
 * und speichert sie als Jetpack-Ink-Stroke. Nur Stifteingaben zeichnen – Finger
 * und Handballen werden ignoriert.
 */
@Composable
fun OnyxPenSurface(
    strokes: List<Stroke>,
    brush: Brush,
    isEraserMode: Boolean,
    onStrokesFinished: (List<Stroke>) -> Unit,
    onEraseStart: () -> Unit,
    onErase: (worldX: Float, worldY: Float) -> Unit,
    onEraseEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember { OnyxPenController(CanvasStrokeRenderer.create()) }

    SideEffect {
        controller.brush = brush
        controller.onStrokesFinished = onStrokesFinished
        controller.onEraseStart = onEraseStart
        controller.onErase = onErase
        controller.onEraseEnd = onEraseEnd
        controller.setEraserMode(isEraserMode)
        controller.setStrokes(strokes)
    }

    // Beim Pausieren der App Raw-Drawing abschalten, sonst zeichnet der Stift
    // systemweit weiter.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.setActive(true)
                Lifecycle.Event.ON_PAUSE -> controller.setActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.aspectRatio(CARD_ASPECT_RATIO, matchHeightConstraintsFirst = true),
        factory = { context -> SurfaceView(context).also(controller::attach) },
        onRelease = { controller.detach() },
    )
}

private class OnyxPenController(
    private val renderer: CanvasStrokeRenderer,
) : SurfaceHolder.Callback {

    var brush: Brush? = null
        set(value) {
            if (field == value) return
            field = value
            if (rawDrawingOpen) touchHelper?.let { applyRawStyle(it) }
        }
    var onStrokesFinished: (List<Stroke>) -> Unit = {}
    var onEraseStart: () -> Unit = {}
    var onErase: (Float, Float) -> Unit = { _, _ -> }
    var onEraseEnd: () -> Unit = {}

    private var view: SurfaceView? = null
    private var touchHelper: TouchHelper? = null
    private var rawDrawingOpen = false
    private var active = true
    private var eraserMode = false
    private var strokes: List<Stroke> = emptyList()

    /** Bildschirmpixel pro Welteinheit. Wird aus dem Raw-Input-Thread gelesen. */
    @Volatile
    private var scale = 1f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxPressure: Float =
        runCatching { EpdController.getMaxTouchPressure() }.getOrNull()?.takeIf { it > 0f } ?: 4096f

    // ------------------------------------------------------------ Lifecycle

    @SuppressLint("ClickableViewAccessibility")
    fun attach(surfaceView: SurfaceView) {
        view = surfaceView
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, event -> handleEraserTouch(event) }
        touchHelper = TouchHelper.create(surfaceView, rawInputCallback)
    }

    fun detach() {
        closeRawDrawing()
        touchHelper = null
        view?.setOnTouchListener(null)
        view?.holder?.removeCallback(this)
        view = null
    }

    fun setActive(isActive: Boolean) {
        active = isActive
        applyRawDrawingEnabled()
    }

    fun setEraserMode(enabled: Boolean) {
        if (enabled == eraserMode) return
        eraserMode = enabled
        applyRawDrawingEnabled()
    }

    fun setStrokes(newStrokes: List<Stroke>) {
        val old = strokes
        if (newStrokes === old) return
        strokes = newStrokes
        // Neuer Stiftstrich angehängt: Der Stift hat ihn schon selbst auf den Bildschirm
        // gezeichnet. Bei Radieren/Rückgängig muss die Raw-Ebene neu aufgebaut werden –
        // ebenso nach einem Textmarker, damit er UNTER der Schrift landet.
        val isAppend = newStrokes.size >= old.size && old.indices.all { newStrokes[it] === old[it] }
        val appendedHighlighter = isAppend &&
            newStrokes.subList(old.size, newStrokes.size).any { it.isHighlighter }
        redraw(refreshRawLayer = !isAppend || appendedHighlighter)
    }

    private val shouldRawDraw get() = rawDrawingOpen && active && !eraserMode

    private fun applyRawDrawingEnabled() {
        touchHelper?.setRawDrawingEnabled(shouldRawDraw)
    }

    private fun closeRawDrawing() {
        if (rawDrawingOpen) {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
            rawDrawingOpen = false
        }
    }

    // -------------------------------------------------------- SurfaceHolder

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val surfaceView = view ?: return
        val helper = touchHelper ?: return
        if (width <= 0) return
        scale = width / WORLD_WIDTH

        closeRawDrawing()
        val limit = Rect().also { surfaceView.getLocalVisibleRect(it) }
        try {
            applyRawStyle(helper)
            helper.setLimitRect(limit, emptyList()).openRawDrawing()
            rawDrawingOpen = true
            applyRawStyle(helper)
        } catch (e: Exception) {
            Log.e(TAG, "Onyx Raw-Drawing konnte nicht gestartet werden", e)
        }
        applyRawDrawingEnabled()
        redraw(refreshRawLayer = true)
    }

    /** Stiftdarstellung des E-Ink-Controllers passend zum aktuellen Werkzeug. */
    private fun applyRawStyle(helper: TouchHelper) {
        val current = brush ?: return
        try {
            if (current.family == StockBrushes.highlighter()) {
                helper.setStrokeStyle(TouchHelper.STROKE_STYLE_MARKER)
                    .setStrokeColor(Color.YELLOW)
                    .setStrokeWidth(current.size * scale)
            } else {
                helper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                    .setStrokeColor(Color.BLACK)
                    .setStrokeWidth(current.size * scale * 0.8f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stiftstil konnte nicht gesetzt werden", e)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        closeRawDrawing()
    }

    // -------------------------------------------------------------- Zeichnen

    private fun redraw(refreshRawLayer: Boolean) {
        val holder = view?.holder ?: return
        if (!holder.surface.isValid) return
        val pauseRaw = refreshRawLayer && shouldRawDraw
        if (pauseRaw) touchHelper?.setRawDrawingEnabled(false)
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.WHITE)
            canvas.drawCardGuides(scale)
            canvas.drawInkStrokes(strokes, renderer, scale)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        if (pauseRaw) touchHelper?.setRawDrawingEnabled(true)
    }

    // ----------------------------------------------------------- Stift-Input

    private fun TouchPoint.toWorld(): PenPoint =
        PenPoint(x = x / scale, y = y / scale, pressure = pressure / maxPressure, timeMillis = timestamp)

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, point: TouchPoint) = Unit
        override fun onEndRawDrawing(b: Boolean, point: TouchPoint) = Unit
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) = Unit

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            val points = list.points.map { it.toWorld() }
            mainHandler.post {
                val currentBrush = brush ?: return@post
                strokeFromPenPoints(points, currentBrush)?.let { onStrokesFinished(listOf(it)) }
            }
        }

        // Radiergummi-Ende des Boox-Stifts (bzw. Seitentaste).
        override fun onBeginRawErasing(b: Boolean, point: TouchPoint) = Unit
        override fun onEndRawErasing(b: Boolean, point: TouchPoint) = Unit
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) = Unit

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            val points = list.points.map { it.toWorld() }
            mainHandler.post {
                onEraseStart()
                points.forEach { onErase(it.x, it.y) }
                onEraseEnd()
            }
        }
    }

    /** Radierer-Werkzeug (Raw-Drawing ist dann aus): nur Stift, kein Finger. */
    private fun handleEraserTouch(event: MotionEvent): Boolean {
        if (!eraserMode) return false
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return true
        val s = scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onEraseStart()
                onErase(event.x / s, event.y / s)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    onErase(event.getHistoricalX(i) / s, event.getHistoricalY(i) / s)
                }
                onErase(event.x / s, event.y / s)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEraseEnd()
        }
        return true
    }
}
