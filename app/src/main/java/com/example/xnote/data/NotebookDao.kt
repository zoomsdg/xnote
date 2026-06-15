package com.example.xnote.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 标签页数据访问对象
 */
@Dao
interface NotebookDao {

    @Query("SELECT * FROM notebooks ORDER BY `order` ASC, createdAt ASC")
    fun getAllNotebooks(): Flow<List<Notebook>>

    @Query("SELECT * FROM notebooks ORDER BY `order` ASC, createdAt ASC")
    suspend fun getAllNotebooksOnce(): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE id = :notebookId")
    suspend fun getNotebookById(notebookId: String): Notebook?

    @Query("SELECT COUNT(*) FROM notebooks WHERE id = :notebookId")
    suspend fun notebookExists(notebookId: String): Int

    @Query("SELECT COALESCE(MAX(`order`), -1) FROM notebooks")
    suspend fun getMaxOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotebook(notebook: Notebook)

    @Update
    suspend fun updateNotebook(notebook: Notebook)

    @Delete
    suspend fun deleteNotebook(notebook: Notebook)
}
