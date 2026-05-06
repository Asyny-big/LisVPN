package com.lisvpn.android.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "server",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"])],
)
data class ServerEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val displayName: String,
    val countryCode: String?,
    val outboundJson: String,
    val rawUri: String,
    val tagsCsv: String,
    val createdAtMs: Long,
)
