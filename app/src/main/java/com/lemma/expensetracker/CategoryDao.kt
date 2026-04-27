package com.lemma.expensetracker

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity)

    @Query("SELECT * FROM categories ORDER BY id DESC")
    fun getAll(): Flow<List<CategoryEntity>>

    @Query("DELETE FROM categories WHERE name = :categoryName")
    suspend fun deleteCategory(categoryName: String)

}