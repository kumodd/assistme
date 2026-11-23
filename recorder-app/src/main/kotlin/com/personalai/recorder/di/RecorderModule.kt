package com.personalai.recorder.di

import android.content.Context
import androidx.room.Room
import com.personalai.recorder.data.db.AudioSegmentDao
import com.personalai.recorder.data.db.DiagnosticDao
import com.personalai.recorder.data.db.ProcessedCommandDao
import com.personalai.recorder.data.db.RecorderDatabase
import com.personalai.recorder.data.db.RecorderDeviceDao
import com.personalai.recorder.data.db.SessionDao
import com.personalai.recorder.data.db.SyncQueueDao
import com.personalai.recorder.security.AndroidKeystoreMasterKeyProvider
import com.personalai.recorder.security.MasterKeyProvider
import com.personalai.recorder.security.SegmentCrypto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecorderModule {

    @Provides
    @Singleton
    fun provideRecorderDatabase(@ApplicationContext context: Context): RecorderDatabase {
        return Room.databaseBuilder(
            context,
            RecorderDatabase::class.java,
            "recorder_db"
        ).build()
    }

    @Provides
    fun provideDeviceDao(db: RecorderDatabase): RecorderDeviceDao = db.recorderDeviceDao()

    @Provides
    fun provideSessionDao(db: RecorderDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideAudioSegmentDao(db: RecorderDatabase): AudioSegmentDao = db.audioSegmentDao()

    @Provides
    fun provideSyncQueueDao(db: RecorderDatabase): SyncQueueDao = db.syncQueueDao()

    @Provides
    fun provideDiagnosticDao(db: RecorderDatabase): DiagnosticDao = db.diagnosticDao()

    @Provides
    fun provideProcessedCommandDao(db: RecorderDatabase): ProcessedCommandDao = db.processedCommandDao()

    @Provides
    @Singleton
    fun provideMasterKeyProvider(impl: AndroidKeystoreMasterKeyProvider): MasterKeyProvider = impl

    @Provides
    @Singleton
    fun provideSegmentCrypto(keyProvider: MasterKeyProvider): SegmentCrypto = SegmentCrypto(keyProvider)
}
