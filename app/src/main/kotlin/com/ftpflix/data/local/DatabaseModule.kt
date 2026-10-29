package com.ftpflix.data.local

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FtpDatabase =
        Room.databaseBuilder(context, FtpDatabase::class.java, FtpDatabase.NAME)
            .addMigrations(
                FtpDatabase.MIGRATION_4_5,
                FtpDatabase.MIGRATION_5_6,
                FtpDatabase.MIGRATION_6_7,
                FtpDatabase.MIGRATION_7_8,
                FtpDatabase.MIGRATION_8_9
            )
            // Pre-v4 schemas had no data worth keeping; v4+ upgrades preserve it.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
            .build()

    @Provides
    fun provideCardDao(db: FtpDatabase): CardDao = db.cardDao()

    @Provides
    fun provideMetadataDao(db: FtpDatabase): MetadataDao = db.metadataDao()

    @Provides
    fun provideHistoryDao(db: FtpDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideMyListDao(db: FtpDatabase): MyListDao = db.myListDao()

    @Provides
    fun provideResolvedStreamDao(db: FtpDatabase): ResolvedStreamDao = db.resolvedStreamDao()

    @Provides
    fun provideFeedItemDao(db: FtpDatabase): FeedItemDao = db.feedItemDao()
}
