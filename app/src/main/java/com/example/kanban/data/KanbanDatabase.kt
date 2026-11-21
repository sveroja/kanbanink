package com.example.kanban.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Eine Kanban-Karte. [strokes] enthält die handschriftlichen Striche,
 * kodiert mit [com.example.kanban.ink.StrokeCodec] (keine Bitmaps).
 */
@Entity(tableName = "cards")
class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Spaltenindex: 0 = Backlog, 1 = In Arbeit, 2 = Erledigt. */
    val lane: Int,
    /** Sortierung innerhalb der Spalte (aufsteigend). */
    val position: Long,
    val strokes: ByteArray,
    val updatedAt: Long,
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY lane, position")
    fun observeAll(): Flow<List<CardEntity>>

    @Insert
    suspend fun insert(card: CardEntity): Long

    @Query("UPDATE cards SET strokes = :strokes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStrokes(id: Long, strokes: ByteArray, updatedAt: Long)

    @Query("UPDATE cards SET lane = :lane, position = :position WHERE id = :id")
    suspend fun move(id: Long, lane: Int, position: Long)

    @Query("SELECT COALESCE(MAX(position), 0) FROM cards WHERE lane = :lane")
    suspend fun maxPosition(lane: Int): Long

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [CardEntity::class], version = 1, exportSchema = false)
abstract class KanbanDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        @Volatile
        private var instance: KanbanDatabase? = null

        fun get(context: Context): KanbanDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KanbanDatabase::class.java,
                    "kanban.db",
                ).build().also { instance = it }
            }
    }
}
