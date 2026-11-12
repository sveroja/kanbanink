package com.example.kanban.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kanban.ink.InkEditorSurface
import com.example.kanban.ink.OnyxPenSurface
import com.example.kanban.ink.isOnyxDevice
import com.example.kanban.ink.StrokeThumbnail

@Composable
fun KanbanApp(viewModel: BoardViewModel, modifier: Modifier = Modifier) {
    val lanes by viewModel.lanes.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(Color.White)) {
        BoardScreen(
            lanes = lanes,
            onAddCard = viewModel::newCard,
            onOpenCard = viewModel::editCard,
            onMoveCard = viewModel::moveCard,
            onDeleteCard = viewModel::deleteCard,
        )
        editor?.let { state ->
            // Zurück-Geste speichert, damit nichts Geschriebenes verloren geht.
            BackHandler { viewModel.saveEditor() }
            CardEditor(state = state, viewModel = viewModel)
        }
    }
}

// ------------------------------------------------------------------- Board

@Composable
private fun BoardScreen(
    lanes: Map<Int, List<Card>>,
    onAddCard: (Int) -> Unit,
    onOpenCard: (Card) -> Unit,
    onMoveCard: (Card, Int) -> Unit,
    onDeleteCard: (Card) -> Unit,
) {
    var cardToDelete by remember { mutableStateOf<Card?>(null) }

    Row(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LANE_TITLES.forEachIndexed { laneIndex, title ->
            val cards = lanes[laneIndex].orEmpty()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(2.dp, Color.Black),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$title (${cards.size})",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { onAddCard(laneIndex) },
                        border = BorderStroke(2.dp, Color.Black),
                    ) { Text("+ Karte", fontSize = 18.sp) }
                }
                HorizontalDivider(thickness = 2.dp, color = Color.Black)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        KanbanCard(
                            card = card,
                            onOpen = { onOpenCard(card) },
                            onMoveLeft = if (laneIndex > 0) ({ onMoveCard(card, -1) }) else null,
                            onMoveRight = if (laneIndex < LANE_TITLES.lastIndex) ({ onMoveCard(card, +1) }) else null,
                            onDelete = { cardToDelete = card },
                        )
                    }
                }
            }
        }
    }

    cardToDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { cardToDelete = null },
            title = { Text("Karte löschen?") },
            text = { StrokeThumbnail(strokes = card.strokes, modifier = Modifier.border(1.dp, Color.Black)) },
            confirmButton = {
                TextButton(onClick = { onDeleteCard(card); cardToDelete = null }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { cardToDelete = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun KanbanCard(
    card: Card,
    onOpen: () -> Unit,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color.Black)
            .background(Color.White),
    ) {
        // Kein Ripple/Indication: auf E-Ink erzeugt das nur Ghosting.
        StrokeThumbnail(
            strokes = card.strokes,
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onOpen),
        )
        HorizontalDivider(color = Color.Black)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardAction("◀", enabled = onMoveLeft != null) { onMoveLeft?.invoke() }
            Spacer(Modifier.weight(1f))
            CardAction("✕", enabled = true, onClick = onDelete)
            Spacer(Modifier.weight(1f))
            CardAction("▶", enabled = onMoveRight != null) { onMoveRight?.invoke() }
        }
    }
}

@Composable
private fun CardAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, fontSize = 22.sp, color = if (enabled) Color.Black else Color.LightGray)
    }
}

// ------------------------------------------------------------------ Editor

@Composable
private fun CardEditor(state: EditorState, viewModel: BoardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            // Klicks nicht an das Board darunter durchreichen.
            .clickable(interactionSource = null, indication = null, onClick = {})
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolButton("Abbrechen", onClick = viewModel::discardEditor)
            Text(
                text = if (state.cardId == null) "Neue Karte – ${LANE_TITLES[state.lane]}" else "Karte bearbeiten",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            ToolButton("Fertig", selected = true, onClick = viewModel::saveEditor)
        }

        Spacer(Modifier.padding(4.dp))

        // Werkzeuge in eigener Zeile, damit es auch im Hochformat passt.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            ToolButton("Stift", selected = state.tool == EditorTool.PEN) { viewModel.setTool(EditorTool.PEN) }
            ToolButton("Marker", selected = state.tool == EditorTool.HIGHLIGHTER) {
                viewModel.setTool(EditorTool.HIGHLIGHTER)
            }
            ToolButton("Radierer", selected = state.tool == EditorTool.ERASER) { viewModel.setTool(EditorTool.ERASER) }
            ToolButton("↶", enabled = state.canUndo, onClick = viewModel::undo)
            ToolButton("↷", enabled = state.canRedo, onClick = viewModel::redo)
            ToolButton("Leeren", enabled = state.strokes.isNotEmpty(), onClick = viewModel::clearAll)
        }

        Spacer(Modifier.padding(12.dp))

        val surfaceModifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .border(2.dp, Color.Black)
        if (isOnyxDevice) {
            // Boox: Onyx Pen SDK mit E-Ink-Schnellzeichnen, nur Stift-Eingabe.
            OnyxPenSurface(
                strokes = state.strokes,
                brush = viewModel.brushFor(state.tool),
                isEraserMode = state.tool == EditorTool.ERASER,
                onStrokesFinished = viewModel::onStrokesFinished,
                onEraseStart = viewModel::startErase,
                onErase = viewModel::erase,
                onEraseEnd = viewModel::endErase,
                modifier = surfaceModifier,
            )
        } else {
            // Andere Geräte/Emulator: Jetpack Ink.
            InkEditorSurface(
                strokes = state.strokes,
                brush = viewModel.brushFor(state.tool),
                isEraserMode = state.tool == EditorTool.ERASER,
                onStrokesFinished = viewModel::onStrokesFinished,
                onEraseStart = viewModel::startErase,
                onErase = viewModel::erase,
                onEraseEnd = viewModel::endErase,
                modifier = surfaceModifier,
            )
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content = if (selected) Color.White else Color.Black
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(2.dp, if (enabled) Color.Black else Color.LightGray),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color.Black else Color.White,
            contentColor = content,
        ),
    ) { Text(label, fontSize = 18.sp) }
}
