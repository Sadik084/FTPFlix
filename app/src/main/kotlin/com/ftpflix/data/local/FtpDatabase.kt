package com.ftpflix.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CardEntity::class,
        MetadataEntity::class,
        HistoryEntity::class,
        MyListEntity::class,
        ResolvedStreamEntity::class,
        FeedItemEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class FtpDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun metadataDao(): MetadataDao
    abstract fun historyDao(): HistoryDao
    abstract fun myListDao(): MyListDao
    abstract fun resolvedStreamDao(): ResolvedStreamDao
    abstract fun feedItemDao(): FeedItemDao

    companion object {
        const val NAME = "ftpflix.db"

        /** resolved_streams: unique-index key -> primary key (upsert fix). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE resolved_streams_new (" +
                        "key TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, year INTEGER, url TEXT NOT NULL, " +
                        "host TEXT NOT NULL, sizeDisplay TEXT NOT NULL, resolvedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO resolved_streams_new " +
                        "(key, title, year, url, host, sizeDisplay, resolvedAt) " +
                        "SELECT key, title, year, url, host, sizeDisplay, resolvedAt FROM resolved_streams"
                )
                db.execSQL("DROP TABLE resolved_streams")
                db.execSQL("ALTER TABLE resolved_streams_new RENAME TO resolved_streams")
            }
        }

        /** h5ai source removed; its listing cache is obsolete. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS listing_cache")
            }
        }

        /** history.episodeLabel: series entries keep their episode metadata. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN episodeLabel TEXT")
            }
        }

        /** metadata_details.imdbId/numberOfSeasons: real IMDb ids for the RoarZone Emby source. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE metadata_details ADD COLUMN imdbId TEXT")
                db.execSQL("ALTER TABLE metadata_details ADD COLUMN numberOfSeasons INTEGER")
            }
        }

        /** metadata_details.ageRating: poster age-rating tags. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE metadata_details ADD COLUMN ageRating TEXT")
            }
        }
    }
}
