package com.lisvpn.android.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "health_snapshot",
    indices = [Index("serverId"), Index("timestampMs")],
)
data class HealthSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val timestampMs: Long,
    val tcpHandshakeMs: Int?,
    val tlsHandshakeMs: Int?,
    val httpRttMs: Int?,
    val success: Boolean,
    val networkType: String,
)
