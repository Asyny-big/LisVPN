package com.lisvpn.android.core.domain.repository

import com.lisvpn.android.core.domain.model.Server

interface AutoOptimizerRepository {
    fun schedule(servers: List<Server>)
    fun cancel()
}
