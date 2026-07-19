package com.threatloom.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_chats` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `article_id` INTEGER NOT NULL,
                `title` TEXT,
                `messages` TEXT,
                `total_cost` REAL NOT NULL,
                `model_used` TEXT,
                `created_date` TEXT DEFAULT CURRENT_TIMESTAMP,
                `updated_date` TEXT DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(`article_id`) REFERENCES `articles`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_saved_chats_article_id` ON `saved_chats` (`article_id`)"
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_category_chats` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `category_name` TEXT NOT NULL,
                `title` TEXT,
                `messages` TEXT,
                `total_cost` REAL NOT NULL,
                `model_used` TEXT,
                `created_date` TEXT DEFAULT CURRENT_TIMESTAMP,
                `updated_date` TEXT DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_saved_category_chats_category_name` ON `saved_category_chats` (`category_name`)"
        )
    }
}
