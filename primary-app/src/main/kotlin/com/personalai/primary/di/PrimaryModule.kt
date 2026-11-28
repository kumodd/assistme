package com.personalai.primary.di

import android.content.Context
import androidx.room.Room
import com.personalai.primary.data.db.CommandAuditDao
import com.personalai.primary.data.db.MemoryDao
import com.personalai.primary.data.db.PairedRecorderDao
import com.personalai.primary.data.db.PrimaryDatabase
import com.personalai.primary.data.db.SyncedContentDao
import com.personalai.primary.data.db.TranscriptDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PrimaryModule {

    @Provides
    @Singleton
    fun providePrimaryDatabase(@ApplicationContext context: Context): PrimaryDatabase {
        return Room.databaseBuilder(
            context,
            PrimaryDatabase::class.java,
            "primary_db"
        )
            .addMigrations(
                PrimaryDatabase.MIGRATION_1_2,
                PrimaryDatabase.MIGRATION_2_3,
                PrimaryDatabase.MIGRATION_3_4,
                PrimaryDatabase.MIGRATION_4_5
            )
            .build()
    }

    @Provides
    fun providePairedRecorderDao(db: PrimaryDatabase): PairedRecorderDao = db.pairedRecorderDao()

    @Provides
    fun provideCommandAuditDao(db: PrimaryDatabase): CommandAuditDao = db.commandAuditDao()

    @Provides
    fun provideSyncedContentDao(db: PrimaryDatabase): SyncedContentDao = db.syncedContentDao()

    @Provides
    fun provideTranscriptDao(db: PrimaryDatabase): TranscriptDao = db.transcriptDao()

    @Provides
    fun provideMemoryDao(db: PrimaryDatabase): MemoryDao = db.memoryDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
