package com.example.xnote.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 记事数据访问对象
 */
@Dao
interface NoteDao {
    
    @Query("SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllNotes(): Flow<List<Note>>
    
    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: String): Note?
    
    @Query("SELECT * FROM note_blocks WHERE noteId = :noteId ORDER BY `order` ASC")
    suspend fun getBlocksByNoteId(noteId: String): List<NoteBlock>
    
    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun getNoteSummaries(): Flow<List<NoteSummary>>
    
    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE n.title LIKE '%' || :searchQuery || '%'
        OR b.text LIKE '%' || :searchQuery || '%'
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun searchNoteSummaries(searchQuery: String): Flow<List<NoteSummary>>
    
    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE (n.title LIKE '%' || :searchQuery || '%'
        OR b.text LIKE '%' || :searchQuery || '%')
        AND n.updatedAt BETWEEN :startTime AND :endTime
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun searchNoteSummariesWithTimeRange(
        searchQuery: String, 
        startTime: Long, 
        endTime: Long
    ): Flow<List<NoteSummary>>
    
    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE (n.title LIKE '%' || :searchQuery || '%'
        OR b.text LIKE '%' || :searchQuery || '%'
        OR n.updatedAt BETWEEN :startTime AND :endTime)
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun searchNoteSummariesWithDateOrText(
        searchQuery: String, 
        startTime: Long, 
        endTime: Long
    ): Flow<List<NoteSummary>>
    
    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE n.updatedAt BETWEEN :startTime AND :endTime
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun getNoteSummariesInTimeRange(startTime: Long, endTime: Long): Flow<List<NoteSummary>>
    
    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE n.categoryId = :categoryId
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun getNoteSummariesByCategory(categoryId: String): Flow<List<NoteSummary>>

    // ---------- 标签页（tab）作用域内的列表/搜索 ----------

    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE n.notebookId = :notebookId
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun getNoteSummariesByNotebook(notebookId: String): Flow<List<NoteSummary>>

    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE n.notebookId = :notebookId AND n.categoryId = :categoryId
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun getNoteSummariesByNotebookAndCategory(
        notebookId: String,
        categoryId: String
    ): Flow<List<NoteSummary>>

    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE n.notebookId = :notebookId
        AND (n.title LIKE '%' || :searchQuery || '%'
        OR b.text LIKE '%' || :searchQuery || '%')
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun searchNoteSummariesInNotebook(
        notebookId: String,
        searchQuery: String
    ): Flow<List<NoteSummary>>

    @Query("""
        SELECT
        n.id as id,
        n.title as title,
        COALESCE(GROUP_CONCAT(CASE WHEN b.type = 'TEXT' THEN b.text ELSE '' END, ' '), '') as preview,
        n.updatedAt as lastModified,
        COUNT(b.id) as blockCount,
        n.categoryId as categoryId,
        n.isPinned as isPinned
        FROM notes n
        LEFT JOIN note_blocks b ON n.id = b.noteId
        WHERE n.notebookId = :notebookId
        AND (n.title LIKE '%' || :searchQuery || '%'
        OR b.text LIKE '%' || :searchQuery || '%'
        OR n.updatedAt BETWEEN :startTime AND :endTime)
        GROUP BY n.id
        ORDER BY n.isPinned DESC, n.updatedAt DESC
    """)
    fun searchNoteSummariesWithDateOrTextInNotebook(
        notebookId: String,
        searchQuery: String,
        startTime: Long,
        endTime: Long
    ): Flow<List<NoteSummary>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<NoteBlock>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: NoteBlock)
    
    @Update
    suspend fun updateNote(note: Note)
    
    @Update
    suspend fun updateBlock(block: NoteBlock)
    
    @Delete
    suspend fun deleteNote(note: Note)
    
    @Query("DELETE FROM note_blocks WHERE noteId = :noteId")
    suspend fun deleteBlocksByNoteId(noteId: String)
    
    @Query("DELETE FROM note_blocks WHERE id = :blockId")
    suspend fun deleteBlock(blockId: String)
    
    @Query("SELECT * FROM notes WHERE categoryId = :categoryId")
    suspend fun getAllNotesInCategory(categoryId: String): List<Note>

    @Query("SELECT * FROM notes WHERE notebookId = :notebookId")
    suspend fun getAllNotesInNotebook(notebookId: String): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE notebookId = :notebookId")
    suspend fun countNotesInNotebook(notebookId: String): Int

    /** 删除标签页时把其下纪事整体迁回另一个标签页（通常是默认标签页） */
    @Query("UPDATE notes SET notebookId = :toNotebookId WHERE notebookId = :fromNotebookId")
    suspend fun moveNotesToNotebook(fromNotebookId: String, toNotebookId: String)
    
    @Transaction
    suspend fun saveFullNote(fullNote: FullNote) {
        insertNote(fullNote.note)
        deleteBlocksByNoteId(fullNote.note.id)
        insertBlocks(fullNote.blocks)
    }
    
    @Transaction
    suspend fun getFullNote(noteId: String): FullNote? {
        val note = getNoteById(noteId) ?: return null
        val blocks = getBlocksByNoteId(noteId)
        return FullNote(note, blocks)
    }
    
    @Transaction
    suspend fun deleteFullNote(noteId: String) {
        val note = getNoteById(noteId)
        if (note != null) {
            deleteNote(note)
            deleteBlocksByNoteId(noteId)
        }
    }
    
    @Query("UPDATE notes SET isPinned = :isPinned WHERE id = :noteId")
    suspend fun updateNotePinStatus(noteId: String, isPinned: Boolean)

    @Query("DELETE FROM notes")
    suspend fun deleteAllNotes()
    
    @Query("DELETE FROM note_blocks")
    suspend fun deleteAllBlocks()
}