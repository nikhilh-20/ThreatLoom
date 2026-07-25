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

/**
 * Additive-only: persist a category chat's rolling retrieval context (article ids + injected sections)
 * so a resumed conversation keeps its grounding. No existing data is touched or dropped.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `saved_category_chats` ADD COLUMN `context_articles` TEXT")
    }
}

/**
 * Additive-only: same rolling-context persistence for saved debates, so a resumed debate keeps its
 * grounding. No existing data is touched or dropped.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debates` ADD COLUMN `context_articles` TEXT")
    }
}

/**
 * Additive-only: create the table backing saved Intelligence chats. The Intelligence tab is a single
 * database-wide feed, so (unlike saved_category_chats) there is no discriminator column or index.
 * No existing data is touched or dropped.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_intelligence_chats` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT,
                `messages` TEXT,
                `context_articles` TEXT,
                `total_cost` REAL NOT NULL,
                `model_used` TEXT,
                `created_date` TEXT DEFAULT CURRENT_TIMESTAMP,
                `updated_date` TEXT DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
    }
}
