package com.lemma.expensetracker

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions ORDER BY id DESC")
    fun getAll(): Flow<List<TransactionEntity>>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM transactions WHERE bank = :categoryName")
    suspend fun deleteByCategory(categoryName: String)

    @Query("UPDATE transactions SET bank = :newName WHERE bank = :oldName")
    suspend fun updateCategoryName(oldName: String, newName: String)
}