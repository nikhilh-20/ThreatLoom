package com.threatloom.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.threatloom.app.data.local.entity.CategoryInsightEntity

@Dao
interface CategoryInsightDao {
    @Query("SELECT * FROM category_insights WHERE category_name = :categoryName")
    suspend fun getByCategory(categoryName: String): CategoryInsightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(insight: CategoryInsightEntity)

    @Query("DELETE FROM category_insights")
    suspend fun deleteAll()
}
