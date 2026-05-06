package com.lisvpn.android.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "profile",
    indices = [
        Index(value = ["sourceType", "sourceValue"], unique = true),
        Index(value = ["isPrimary"]),
    ],
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceType: String,
    val sourceValue: String,
    val expiresAtMs: Long?,
    val updateIntervalHours: Int?,
    val announceMessage: String?,
    val createdAtMs: Long,
    val lastRefreshedAtMs: Long?,
    val isPrimary: Boolean,
)
