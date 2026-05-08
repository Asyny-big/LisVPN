package com.lisvpn.android.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "smart_server_cache",
    primaryKeys = ["networkKey", "serverId"],
    indices = [
        Index("serverId"),
        Index("networkKey"),
        Index("updatedAtMs"),
    ],
)
data class SmartServerCacheEntity(
    val networkKey: String,
    val serverId: String,
    val networkClass: String,
    val networkFingerprint: String,
    val mobileOperator: String?,
    val asn: String?,
    val lastScore: Double,
    val successCount: Int,
    val failureCount: Int,
    val lastLatencyMs: Int?,
    val lastThroughputKbps: Long?,
    val lastJitterMs: Int?,
    val lastPacketLoss: Double,
    val telegramReachable: Boolean,
    val youtubeReachable: Boolean,
    val lastSuccessAtMs: Long?,
    val updatedAtMs: Long,
)
