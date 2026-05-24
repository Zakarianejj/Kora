package com.example.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

class GameRepository(private val gameDao: GameDao) {

    val userStats: Flow<UserStatsEntity?> = gameDao.getUserStats()
    val matchHistory: Flow<List<MatchHistoryEntity>> = gameDao.getMatchHistory()

    suspend fun saveUserStats(stats: UserStatsEntity) {
        gameDao.insertUserStats(stats)
    }

    suspend fun addMatchLog(log: MatchHistoryEntity) {
        gameDao.insertMatchHistory(log)
    }

    suspend fun clearHistory() {
        gameDao.clearMatchHistory()
    }

    suspend fun getInitialStatsOrInsert(): UserStatsEntity {
        val current = userStats.firstOrNull()
        if (current == null) {
            val defaultStats = UserStatsEntity()
            saveUserStats(defaultStats)
            return defaultStats
        }
        return current
    }
}
