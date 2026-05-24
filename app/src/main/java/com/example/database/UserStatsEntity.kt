package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val id: Int = 1,
    val highScore: Int = 0,
    val totalGoals: Int = 0,
    val totalShots: Int = 0,
    val coins: Int = 0,
    val unlockedBalls: String = "classic", // Comma-separated like "classic,fireball"
    val selectedBall: String = "classic",
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true
)
