package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "match_history")
data class MatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: String, // "penalty", "freekick", "target"
    val goalsScored: Int,
    val totalShots: Int,
    val successRate: Float,
    val coinsEarned: Int
)
