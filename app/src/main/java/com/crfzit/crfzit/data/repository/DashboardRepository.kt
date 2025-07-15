package com.crfzit.crfzit.data.repository

import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.GlobalStats
import kotlinx.coroutines.flow.Flow

interface DashboardRepository {
    fun getGlobalStatsStream(): Flow<GlobalStats>
    fun getAppRuntimeStateStream(): Flow<List<AppRuntimeState>>
}