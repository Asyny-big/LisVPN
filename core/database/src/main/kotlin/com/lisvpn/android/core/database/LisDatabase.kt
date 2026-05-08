package com.lisvpn.android.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lisvpn.android.core.database.dao.HealthDao
import com.lisvpn.android.core.database.dao.ProfileDao
import com.lisvpn.android.core.database.dao.SmartServerCacheDao
import com.lisvpn.android.core.database.entity.HealthSnapshotEntity
import com.lisvpn.android.core.database.entity.ProfileEntity
import com.lisvpn.android.core.database.entity.ServerEntity
import com.lisvpn.android.core.database.entity.SmartServerCacheEntity

@Database(
    entities = [
        HealthSnapshotEntity::class,
        ProfileEntity::class,
        ServerEntity::class,
        SmartServerCacheEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LisDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao
    abstract fun profileDao(): ProfileDao
    abstract fun smartServerCacheDao(): SmartServerCacheDao

    companion object {
        const val NAME = "lisvpn.db"
    }
}
