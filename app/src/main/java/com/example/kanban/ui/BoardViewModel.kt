package com.example.kanban.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.MutableParallelogram
import androidx.ink.geometry.MutableSegment
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kanban.data.CardEntity
import com.example.kanban.data.KanbanDatabase
import com.example.kanban.ink.StrokeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val LANE_TITLES = listOf("To Do", "In Arbeit", "Erledigt")

class Card(
    val id: Long,
    val lane: Int,
    val strokes: List<Stroke>,
)

data class EditorState(
    /** null = neue Karte. */
    val cardId: Long?,
    val lane: Int,
    val strokes: List<Stroke>,
    val isEraserMode: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

class BoardViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = KanbanDatabase.get(application).cardDao()

    /** Cache dekodierter Striche: id -> (updatedAt, strokes). Nur im Flow-Kontext genutzt. */
    private val decodeCache = HashMap<Long, Pair<Long, List<Stroke>>>()

    /** Karten gruppiert nach Spalte. */
    val lanes: StateFlow<Map<Int, List<Card>>> = dao.observeAll()
        .map { entities ->
            decodeCache.keys.retainAll(entities.map { it.id }.toSet())
            entities.map { it.toCard() }.groupBy { it.lane }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun CardEntity.toCard(): Card {
        val cached = decodeCache[id]
        val strokes = if (cached != null && cached.first == updatedAt) {
            cached.second
        } else {
            StrokeCodec.decode(strokes).also { decodeCache[id] = updatedAt to it }
        }
        return Card(id = id, lane = lane, strokes = strokes)
    }

    // ---------------------------------------------------------------- Board

    fun moveCard(card: Card, delta: Int) {
        val target = (card.lane + delta).coerceIn(LANE_TITLES.indices)
        if (target == card.lane) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.move(card.id, target, dao.maxPosition(target) + 1)
        }
    }

    fun deleteCard(card: Card) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(card.id) }
    }

    // --------------------------------------------------------------- Editor

    /** Stift in Weltkoordinaten (siehe WORLD_WIDTH). */
    val penBrush: Brush = Brush.createWithComposeColor(
        family = StockBrushes.pressurePen(),
        color = Color.Black,
        size = 5f,
        epsilon = 0.1f,
    )

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val history = mutableListOf<List<Stroke>>()
    private var historyIndex = 0
    private val maxHistorySize = 100

    private var previousErasePoint: MutableVec? = null
    private val eraserPadding = 12f // Weltkoordinaten

    fun newCard(lane: Int) = openEditor(EditorState(cardId = null, lane = lane, strokes = emptyList()))

    fun editCard(card: Card) = openEditor(EditorState(cardId = card.id, lane = card.lane, strokes = card.strokes))

    private fun openEditor(state: EditorState) {
        history.clear()
        history.add(state.strokes)
        historyIndex = 0
        _editor.value = state
    }

    private fun pushStrokes(newStrokes: List<Stroke>) {
        if (historyIndex < history.lastIndex) {
            history.subList(historyIndex + 1, history.size).clear()
        }
        history.add(newStrokes)
        historyIndex++
        while (history.size > maxHistorySize) {
            history.removeAt(0)
            historyIndex--
        }
        publishHistory()
    }

    private fun publishHistory() {
        _editor.update {
            it?.copy(
                strokes = history[historyIndex],
                canUndo = historyIndex > 0,
                canRedo = historyIndex < history.lastIndex,
            )
        }
    }

    fun onStrokesFinished(finished: List<Stroke>) {
        pushStrokes(history[historyIndex] + finished)
    }

    fun undo() {
        if (historyIndex > 0) {
            historyIndex--
            publishHistory()
        }
    }

    fun redo() {
        if (historyIndex < history.lastIndex) {
            historyIndex++
            publishHistory()
        }
    }

    fun clearAll() {
        if (history[historyIndex].isNotEmpty()) pushStrokes(emptyList())
    }

    fun setEraserMode(enabled: Boolean) {
        _editor.update { it?.copy(isEraserMode = enabled) }
    }

    fun startErase() {
        previousErasePoint = null
    }

    fun endErase() {
        previousErasePoint = null
    }

    fun erase(x: Float, y: Float) {
        val current = MutableVec(x, y)
        val previous = previousErasePoint ?: current
        previousErasePoint = current
        val eraser = MutableParallelogram()
            .populateFromSegmentAndPadding(MutableSegment(previous, current), eraserPadding)
        val before = history[historyIndex]
        val after = before.filterNot {
            it.shape.computeCoverageIsGreaterThan(eraser, 0f, AffineTransform.IDENTITY)
        }
        if (after.size != before.size) pushStrokes(after)
    }

    /** Speichert und schließt den Editor. Leere neue Karten werden verworfen. */
    fun saveEditor() {
        val state = _editor.value ?: return
        _editor.value = null
        val strokes = state.strokes
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = StrokeCodec.encode(strokes)
            val now = System.currentTimeMillis()
            if (state.cardId != null) {
                dao.updateStrokes(state.cardId, bytes, now)
            } else if (strokes.isNotEmpty()) {
                dao.insert(
                    CardEntity(
                        lane = state.lane,
                        position = dao.maxPosition(state.lane) + 1,
                        strokes = bytes,
                        updatedAt = now,
                    )
                )
            }
        }
    }

    fun discardEditor() {
        _editor.value = null
    }
}
