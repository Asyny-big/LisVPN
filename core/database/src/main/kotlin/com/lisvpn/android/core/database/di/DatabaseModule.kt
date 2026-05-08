package com.lisvpn.android.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lisvpn.android.core.database.LisDatabase
import com.lisvpn.android.core.database.dao.HealthDao
import com.lisvpn.android.core.database.dao.ProfileDao
import com.lisvpn.android.core.database.dao.SmartServerCacheDao
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
    fun provideLisDatabase(@ApplicationContext context: Context): LisDatabase =
        Room.databaseBuilder(context, LisDatabase::class.java, LisDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideHealthDao(database: LisDatabase): HealthDao = database.healthDao()

    @Provides
    fun provideProfileDao(database: LisDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideSmartServerCacheDao(database: LisDatabase): SmartServerCacheDao =
        database.smartServerCacheDao()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `profile` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `sourceValue` TEXT NOT NULL,
                    `expiresAtMs` INTEGER,
                    `updateIntervalHours` INTEGER,
                    `announceMessage` TEXT,
                    `createdAtMs` INTEGER NOT NULL,
                    `lastRefreshedAtMs` INTEGER,
                    `isPrimary` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_sourceType_sourceValue` ON `profile` (`sourceType`, `sourceValue`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_isPrimary` ON `profile` (`isPrimary`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `server` (
                    `id` TEXT NOT NULL,
                    `profileId` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `countryCode` TEXT,
                    `outboundJson` TEXT NOT NULL,
                    `rawUri` TEXT NOT NULL,
                    `tagsCsv` TEXT NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`profileId`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_profileId` ON `server` (`profileId`)")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `smart_server_cache` (
                    `networkKey` TEXT NOT NULL,
                    `serverId` TEXT NOT NULL,
                    `networkClass` TEXT NOT NULL,
                    `networkFingerprint` TEXT NOT NULL,
                    `mobileOperator` TEXT,
                    `asn` TEXT,
                    `lastScore` REAL NOT NULL,
                    `successCount` INTEGER NOT NULL,
                    `failureCount` INTEGER NOT NULL,
                    `lastLatencyMs` INTEGER,
                    `lastThroughputKbps` INTEGER,
                    `lastJitterMs` INTEGER,
                    `lastPacketLoss` REAL NOT NULL,
                    `telegramReachable` INTEGER NOT NULL,
                    `youtubeReachable` INTEGER NOT NULL,
                    `lastSuccessAtMs` INTEGER,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`networkKey`, `serverId`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_server_cache_serverId` ON `smart_server_cache` (`serverId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_server_cache_networkKey` ON `smart_server_cache` (`networkKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_server_cache_updatedAtMs` ON `smart_server_cache` (`updatedAtMs`)")
        }
    }
}
