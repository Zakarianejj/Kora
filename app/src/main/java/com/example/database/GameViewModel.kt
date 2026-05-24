package com.example.database

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.*
import kotlin.random.Random

// Game Modes
enum class GameMode {
    PENALTY,     // ركلات جزاء
    FREE_KICK,   // ركلات حرة
    TARGETS      // الأهداف
}

// Ball States
enum class BallState {
    STILL,
    KICKED,
    SAVED,
    POST_BOUNCE,
    GOAL,
    OUT_OF_BOUNDS
}

// Goalkeeper states
enum class GoalkeeperState {
    STILL,
    DIVING_LEFT,
    DIVING_RIGHT,
    DIVING_UP_LEFT,
    DIVING_UP_RIGHT,
    CELEBRATING_SAVE,
    LAMENTING_GOAL
}

// 3D Point
data class Point3D(var x: Float, var y: Float, var z: Float)

// Target Board representation
data class TargetBoard(
    val id: Int,
    val x: Float,
    val z: Float,
    val radius: Float = 0.5f,
    var active: Boolean = true,
    val points: Int = 100
)

// Particle Effect
data class GameParticle(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    val colorHex: Long,
    val maxSize: Float,
    var size: Float,
    var alpha: Float,
    var age: Int = 0,
    val maxAge: Int = 40
)

// Preset Ball Skins
data class BallSkin(
    val id: String,
    val nameAr: String,
    val descriptionAr: String,
    val price: Int,
    val primaryColor: Long,
    val secondaryColor: Long,
    val particleColor: Long? = null
)

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val database = GameDatabase.getDatabase(application)
    private val repository = GameRepository(database.gameDao)

    // UI and Persistence Streams
    val userStats: StateFlow<UserStatsEntity?> = repository.userStats.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null as UserStatsEntity?
    )

    val matchHistory: StateFlow<List<MatchHistoryEntity>> = repository.matchHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList<MatchHistoryEntity>()
    )

    // Avaliable skins
    val ballSkins = listOf(
        BallSkin("classic", "الكلاسيكية", "الكرة الرياضية التقليدية بالأبيض والأسود", 0, 0xFFFFFFFF, 0xFF111111),
        BallSkin("cyber", "نيون السيبراني", "كرة مشعة بألوان الأزرق والوردي اللامع", 150, 0xFF00E5FF, 0xFFFF007F, 0xFF00FFFF),
        BallSkin("fireball", "كرة اللهب", "تشتعل بألسنة اللهب البرتقالية الحارة", 300, 0xFFFF3D00, 0xFFFFD600, 0xFFFF9100),
        BallSkin("gold", "الكرة الذهبية", "كرة ملكية مطلية بالذهب اللامع لنجوم الفخامة", 600, 0xFFFFD700, 0xFFFFF8E1, 0xFFFFD700)
    )

    // Game Mode State
    var currentMode by mutableStateOf(GameMode.PENALTY)
        private set

    // Game Loop states
    var ballPos by mutableStateOf(Point3D(0f, 11f, 0.11f))
        private set
    var prevBallPos by mutableStateOf(Point3D(0f, 11f, 0.11f))
        private set

    private var ballVel = Point3D(0f, 0f, 0f)
    var ballSpinX by mutableStateOf(0f) // lift / dip
    var ballSpinY by mutableStateOf(0f) // bend left / right

    var ballState by mutableStateOf(BallState.STILL)
        private set

    // Goalkeeper State
    var gkPos by mutableStateOf(Point3D(0f, 24.5f, 1.0f))
        private set
    var gkState by mutableStateOf(GoalkeeperState.STILL)
        private set
    private var gkTargetX = 0f
    private var gkTargetZ = 1.0f

    // Wall Defenders (for Free Kick mode)
    // Placed at Y = 16f if ball is at Y = 24f (around 8m distance)
    var wallX by mutableStateOf(0f)
        private set
    var wallY by mutableStateOf(16f)
        private set
    var wallDefenderWidth = 0.6f
    var wallDefenderCount = 3
    var wallJumpOffset by mutableStateOf(0f)
    private var isWallJumping = false
    private var wallJumpTime = 0f

    // Score states
    var score by mutableStateOf(0)
    var comboStreak by mutableStateOf(0)
    var matchShots by mutableStateOf(0)
    var matchGoals by mutableStateOf(0)
    var coinsEarnedInSession by mutableStateOf(0)
    var showGoalGraphic by mutableStateOf(false)
    var showSaveGraphic by mutableStateOf(false)
    var showMissGraphic by mutableStateOf(false)
    var comboMessage by mutableStateOf("")

    // Floating Coins Indicator: "+15 Coin" animation
    var floatMessage by mutableStateOf("")
    var showFloatMessage by mutableStateOf(false)

    // List of target boards for the target mode
    val targets = mutableStateListOf<TargetBoard>()

    // Particle emissions
    val particles = mutableStateListOf<GameParticle>()

    // Remaining game time for TARGETS mode
    var targetTimerSeconds by mutableStateOf(45)
    private var timerJob: Job? = null

    // Game loop control
    private var gameLoopJob: Job? = null
    var isMuted by mutableStateOf(false)

    init {
        // Ensure user record exists in database
        viewModelScope.launch {
            repository.getInitialStatsOrInsert()
        }
        setupGame()
    }

    fun selectMode(mode: GameMode) {
        currentMode = mode
        saveMatchSessionIfNeeded() // Save previous game metrics
        resetSessionStats()
        setupGame()
    }

    private fun resetSessionStats() {
        score = 0
        comboStreak = 0
        matchShots = 0
        matchGoals = 0
        coinsEarnedInSession = 0
        showGoalGraphic = false
        showSaveGraphic = false
        showMissGraphic = false
        particles.clear()
        if (currentMode == GameMode.TARGETS) {
            targetTimerSeconds = 45
            startTargetsTimer()
        } else {
            timerJob?.cancel()
        }
    }

    fun restartGame() {
        saveMatchSessionIfNeeded()
        resetSessionStats()
        setupGame()
    }

    fun muteToggle() {
        isMuted = !isMuted
        viewModelScope.launch {
            val stats = repository.getInitialStatsOrInsert()
            repository.saveUserStats(stats.copy(soundEnabled = !isMuted))
        }
    }

    private fun startTargetsTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (targetTimerSeconds > 0) {
                delay(1000)
                targetTimerSeconds--
                if (targetTimerSeconds == 0) {
                    // Game Over
                    ballState = BallState.OUT_OF_BOUNDS
                    saveMatchSessionIfNeeded()
                }
            }
        }
    }

    fun setupGame() {
        ballState = BallState.STILL
        particles.clear()
        showGoalGraphic = false
        showSaveGraphic = false
        showMissGraphic = false

        // Randomize goalkeeper status
        gkState = GoalkeeperState.STILL
        gkPos = Point3D(0f, 24.5f, 1.0f)
        gkTargetX = 0f
        gkTargetZ = 1.0f

        when (currentMode) {
            GameMode.PENALTY -> {
                // Ball placed at exact penalty spot (11 meters distance, centered)
                ballPos = Point3D(0f, 13.5f, 0.11f)
                prevBallPos = ballPos.copy()
                ballVel = Point3D(0f, 0f, 0f)
                ballSpinX = 0f
                ballSpinY = 0f
            }
            GameMode.FREE_KICK -> {
                // Ball placed randomly at a typical free kick distance (20 - 24 meters, offset horizontally)
                val randomX = Random.nextFloat() * 4f - 2f // x offset between -2 and +2
                val randomY = 17f + Random.nextFloat() * 4f // y distance between 17 and 21 meters
                ballPos = Point3D(randomX, randomY, 0.11f)
                prevBallPos = ballPos.copy()
                ballVel = Point3D(0f, 0f, 0f)
                ballSpinX = 0f
                ballSpinY = 0f

                // Setup defense wall in front of the ball (approximately 9 meters closer to the goal)
                // Position should block the line of sight from the ball to a portion of the goal
                wallY = ballPos.y - 8f
                // Wall center leans toward the goal center but shifts slightly to align with the angle
                val ballToGoalAngle = atan2(25.0f - ballPos.y, 0f - ballPos.x)
                wallX = ballPos.x + cos(ballToGoalAngle) * 8f
                isWallJumping = false
                wallJumpOffset = 0f
                wallJumpTime = 0f
            }
            GameMode.TARGETS -> {
                // Shoot from various distances
                val randomX = Random.nextFloat() * 3f - 1.5f
                ballPos = Point3D(randomX, 15f, 0.11f)
                prevBallPos = ballPos.copy()
                ballVel = Point3D(0f, 0f, 0f)
                ballSpinX = 0f
                ballSpinY = 0f

                // Re-populate target boards
                populateTargets()
            }
        }
    }

    private fun populateTargets() {
        targets.clear()
        // Add 4 corner targets in the goal
        targets.add(TargetBoard(1, -2.8f, 2.0f, points = 150)) // Top Left
        targets.add(TargetBoard(2, 2.8f, 2.0f, points = 150))  // Top Right
        targets.add(TargetBoard(3, -2.8f, 0.5f, points = 100)) // Bottom Left
        targets.add(TargetBoard(4, 2.8f, 0.5f, points = 100))  // Bottom Right
    }

    // Process player interactive swipe to kick the ball
    fun kickBall(vx: Float, vy: Float, vz: Float, spinX: Float, spinY: Float) {
        if (ballState != BallState.STILL) return

        ballVel = Point3D(vx, vy, vz)
        ballSpinX = spinX
        ballSpinY = spinY
        ballState = BallState.KICKED
        matchShots++

        // Calculate Goalkeeper dive target based on estimated goal intersection
        calculateGoalkeeperAction(vx, vy, vz)

        // Make the defender wall jump if FREE_KICK mode
        if (currentMode == GameMode.FREE_KICK) {
            triggerWallJump()
        }

        // Run standard physics game loop
        runPhysicsLoop()
    }

    private fun triggerWallJump() {
        isWallJumping = true
        wallJumpTime = 0f
    }

    private fun calculateGoalkeeperAction(vx: Float, vy: Float, vz: Float) {
        // Goal position is at Y = 25.0m
        // Estimate how long the ball takes to reach Y = 25.0m
        if (vy <= 0) return
        val dtToGoal = (25.0f - ballPos.y) / vy

        // Estimate coordinate of ball at Y = 25.0
        // We accumulate simplified gravity & spin
        var estX = ballPos.x + vx * dtToGoal
        // Apply spin curve integration approximately
        estX += (ballSpinY * vy * 0.15f) * (0.5f * dtToGoal * dtToGoal)

        var estZ = ballPos.z + vz * dtToGoal + 0.5f * -9.8f * dtToGoal * dtToGoal
        estZ = max(0.1f, estZ)

        // Clamp estimations slightly to realistic bounds
        estX = estX.coerceIn(-4.2f, 4.2f)
        estZ = estZ.coerceIn(0.1f, 3.0f)

        // Determine GK reaction difficulty based on total coins/high score (Goalkeeper AI level scales!)
        val currentHighScore = userStats.value?.highScore ?: 0
        val goalkeeperDifficulty = (currentHighScore / 3).coerceIn(0, 5) // Goalkeeper difficulty level
        
        // Goalkeeper might make an error or reacts with speed depending on difficulty
        val reactsInCorrectDirection = Random.nextFloat() > (0.25f - goalkeeperDifficulty * 0.03f).coerceIn(0.05f, 0.25f)
        
        if (reactsInCorrectDirection) {
            // Dive closer to target with slight offset
            val randomness = (0.3f - goalkeeperDifficulty * 0.05f).coerceAtLeast(0.02f)
            gkTargetX = estX + (Random.nextFloat() * 2f - 1f) * randomness
            gkTargetZ = estZ + (Random.nextFloat() * 2f - 1f) * randomness
        } else {
            // Dive in random wrong direction! Or delay
            gkTargetX = (Random.nextFloat() * 6f - 3f)
            gkTargetZ = 0.5f + Random.nextFloat() * 1.5f
        }

        // Constrain targets to physical goalkeeper constraints
        gkTargetX = gkTargetX.coerceIn(-3.6f, 3.6f)
        gkTargetZ = gkTargetZ.coerceIn(0.1f, 2.3f)

        // Specific animation state
        val left = gkTargetX < -0.6f
        val high = gkTargetZ > 1.3f

        gkState = when {
            left && high -> GoalkeeperState.DIVING_UP_LEFT
            left && !high -> GoalkeeperState.DIVING_LEFT
            !left && high && gkTargetX > 0.6f -> GoalkeeperState.DIVING_UP_RIGHT
            !left && !high && gkTargetX > 0.6f -> GoalkeeperState.DIVING_RIGHT
            else -> GoalkeeperState.STILL
        }
    }

    private fun runPhysicsLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = viewModelScope.launch {
            val dt = 0.016f // Fixed delta time (~60 FPS)
            var active = true
            var frameCount = 0

            while (active) {
                frameCount++
                delay(16) // tick

                // Update wall jumping
                if (isWallJumping) {
                    wallJumpTime += dt * 5f // speed factor
                    // parabolic jumping shape: z = sin(t)*maxHeight
                    if (wallJumpTime < Math.PI) {
                        wallJumpOffset = sin(wallJumpTime).toFloat() * 0.45f // jump 45cm high
                    } else {
                        wallJumpOffset = 0f
                        isWallJumping = false
                    }
                }

                // Goalkeeper movement interpolation
                // Keeper moves fast to intercept
                val gkInterpolationSpeed = 11f * dt
                val dx = gkTargetX - gkPos.x
                val dz = gkTargetZ - gkPos.z
                gkPos = Point3D(
                    gkPos.x + dx * gkInterpolationSpeed,
                    gkPos.y,
                    gkPos.z + dz * gkInterpolationSpeed
                )

                // Ball position before updating (needed for collision detection)
                prevBallPos = ballPos.copy()

                // Calculate forces on the ball
                // 1. Gravity
                ballVel.z += -9.81f * dt

                // 2. Air resistance / Drag
                val speed = sqrt(ballVel.x * ballVel.x + ballVel.y * ballVel.y + ballVel.z * ballVel.z)
                val dragCoef = 0.15f
                if (speed > 0.1f) {
                    ballVel.x -= dragCoef * ballVel.x * dt
                    ballVel.y -= dragCoef * ballVel.y * dt
                    ballVel.z -= dragCoef * ballVel.z * dt
                }

                // 3. Curve / Magnus force
                // Flying spin generates sideways acceleration: ax = spinY * vy * Coefficient
                ballVel.x += ballSpinY * ballVel.y * 0.14f * dt
                // Dipping spin generates vertical acceleration: az = spinX * vy * Coefficient
                ballVel.z += ballSpinX * ballVel.y * 0.08f * dt

                // Update positions
                ballPos = Point3D(
                    ballPos.x + ballVel.x * dt,
                    ballPos.y + ballVel.y * dt,
                    ballPos.z + ballVel.z * dt
                )

                // Emit beautiful skin-specific trail particles
                emitTrailParticles()

                // Particle update
                updateParticles()

                // --- COLLISION CHECKING ---

                // 1. Defending Wall collision (only in Free Kick and before passing wall)
                if (currentMode == GameMode.FREE_KICK && ballPos.y >= wallY && prevBallPos.y < wallY) {
                    // Interpolate ball coordinates at exactly Y = wallY
                    val t = (wallY - prevBallPos.y) / (ballPos.y - prevBallPos.y)
                    val intersectX = prevBallPos.x + (ballPos.x - prevBallPos.x) * t
                    val intersectZ = prevBallPos.z + (ballPos.z - prevBallPos.z) * t

                    // Check if x and z intersect with the wall of players
                    // Wall spans from wallX - totalWidth/2 to wallX + totalWidth/2
                    val wallHalfWidth = (wallDefenderCount * wallDefenderWidth) / 2f
                    val minWallX = wallX - wallHalfWidth
                    val maxWallX = wallX + wallHalfWidth
                    // Wall player height (approx 1.85m + jump offset)
                    val wallMaxZ = 1.85f + wallJumpOffset

                    if (intersectX in minWallX..maxWallX && intersectZ in 0f..wallMaxZ) {
                        // HIT THE WALL!
                        ballVel.y = -ballVel.y * 0.35f // rebounds backwards
                        ballVel.x = (if (intersectX < wallX) -1f else 1f) * (3f + Random.nextFloat() * 4f)
                        ballVel.z = 2f + Random.nextFloat() * 4f
                        ballSpinY = -ballSpinY * 0.5f // reverse spin
                        ballState = BallState.POST_BOUNCE
                        spawnExplosionParticles(ballPos, 0xFFE0E0E0, 15)
                        triggerHapticFeedback()
                    }
                }

                // 2. Head-on collision with Goalkeeper (before the goal line Y = 25.0)
                if (ballPos.y >= 24.3f && ballPos.y <= 24.8f && prevBallPos.y < 24.3f && ballState == BallState.KICKED) {
                    // Check if ball intersects the goalie
                    val gkRadius = 0.95f // wingspan radius
                    val dxGk = ballPos.x - gkPos.x
                    val dzGk = ballPos.z - gkPos.z
                    val distGoalie = sqrt(dxGk * dxGk + dzGk * dzGk)

                    if (distGoalie < gkRadius) {
                        // GK SAVE! (تصدي)
                        ballState = BallState.SAVED
                        ballVel.y = -ballVel.y * 0.4f // rebounds out of goalie
                        ballVel.x = (dxGk / gkRadius) * (8f + Random.nextFloat() * 6f)
                        ballVel.z = (dzGk / gkRadius) * 6f + 3f
                        gkState = GoalkeeperState.CELEBRATING_SAVE

                        showSaveGraphic = true
                        comboStreak = 0
                        spawnExplosionParticles(ballPos, 0xFF00FFCC, 25)
                        triggerHapticFeedback()
                    }
                }

                // 3. Grass ground bounce
                if (ballPos.z <= 0.11f) {
                    if (ballVel.z < -1.5f) {
                        ballPos.z = 0.11f
                        ballVel.z = -ballVel.z * 0.55f // bounce height
                        // Apply grass friction drag
                        ballVel.x *= 0.82f
                        ballVel.y *= 0.82f
                        spawnExplosionParticles(ballPos, 0xFF4CAF50, 6)
                    } else {
                        // Friction on ground rolling status
                        ballPos.z = 0.11f
                        ballVel.z = 0f
                        ballVel.x *= 0.92f
                        ballVel.y *= 0.92f
                    }
                }

                // 4. Goal post / Crossbar hits
                // Posts are at X = -3.6f and X = +3.6f, Y = 25.0f, Z = [0.1..2.44]
                // Crossbar is at X = [-3.6..3.6], Y = 25.0f, Z = 2.44
                if (ballPos.y >= 24.9f && ballPos.y <= 25.2f && prevBallPos.y < 24.9f) {
                    val ballRad = 0.12f

                    // Crossbar check (from x=-3.6 to 3.6, at height z=2.44)
                    if (ballPos.x in -3.65f..3.65f) {
                        val dZ = abs(ballPos.z - 2.44f)
                        if (dZ < ballRad + 0.08f) {
                            // HIT CROSSBAR! (العارضة)
                            ballVel.z = -ballVel.z * 0.65f + 1f
                            ballVel.y = -ballVel.y * 0.55f
                            ballPos.y = 24.8f // push ball back slightly
                            ballState = BallState.POST_BOUNCE
                            spawnExplosionParticles(ballPos, 0xFFFFFB00, 25)
                            triggerHapticFeedback()

                            // Crossbar challenge specific rewards!
                            if (currentMode == GameMode.PENALTY || currentMode == GameMode.FREE_KICK) {
                                score += 200
                                coinsEarnedInSession += 20
                                triggerFloatingText("ضربة عارضة! +200 نقطة")
                            }
                        }
                    }

                    // Left Post check
                    val distToLeftPost = sqrt((ballPos.x - (-3.6f)).pow(2) + (ballPos.z - 1.22f).pow(2))
                    if (abs(ballPos.x - (-3.6f)) < ballRad + 0.08f && ballPos.z <= 2.44f) {
                        ballVel.x = abs(ballVel.x) * 0.7f + 2f
                        ballVel.y = -ballVel.y * 0.55f
                        ballPos.y = 24.8f
                        ballState = BallState.POST_BOUNCE
                        spawnExplosionParticles(ballPos, 0xFFFFFFFF, 20)
                        triggerHapticFeedback()
                        triggerFloatingText("اصطدام بالقائم! +100 نقطة")
                        score += 100
                    }

                    // Right Post check
                    if (abs(ballPos.x - 3.6f) < ballRad + 0.08f && ballPos.z <= 2.44f) {
                        ballVel.x = -abs(ballVel.x) * 0.7f - 2f
                        ballVel.y = -ballVel.y * 0.55f
                        ballPos.y = 24.8f
                        ballState = BallState.POST_BOUNCE
                        spawnExplosionParticles(ballPos, 0xFFFFFFFF, 20)
                        triggerHapticFeedback()
                        triggerFloatingText("اصطدام بالقائم! +100 نقطة")
                        score += 100
                    }
                }

                // 5. Goal Line crossover check (at Y = 25.0f, between X = -3.6f to 3.6f and Z = 0f to 2.44f)
                if (ballPos.y >= 25.0f && prevBallPos.y < 25.0f && ballState == BallState.KICKED) {
                    if (ballPos.x in -3.55f..3.55f && ballPos.z in 0.0f..2.42f) {
                        // GOOOOOOAL!! (هدف)
                        ballState = BallState.GOAL
                        gkState = GoalkeeperState.LAMENTING_GOAL
                        matchGoals++
                        comboStreak++
                        val currentSkin = ballSkins.find { it.id == (userStats.value?.selectedBall ?: "classic") }
                        
                        var coinsForThisGoal = 10
                        if (comboStreak > 1) {
                            coinsForThisGoal += comboStreak * 5
                            comboMessage = "متتالية x$comboStreak! هدف ناري!"
                        } else {
                            comboMessage = "هدف رائع!"
                        }

                        // Target mode check
                        var hitTargetsPoints = 0
                        var targetHitAlert = ""
                        if (currentMode == GameMode.TARGETS) {
                            // Check if ball hit active targets
                            for (target in targets) {
                                if (target.active) {
                                    val dx = ballPos.x - target.x
                                    val dz = ballPos.z - target.z
                                    val dist = sqrt(dx * dx + dz * dz)
                                    if (dist < 0.75f) { // Hit!
                                        target.active = false
                                        hitTargetsPoints += target.points
                                        coinsForThisGoal += 15
                                        targetHitAlert = "تفجير الهدف! +15 عملة"
                                        targetTimerSeconds += 5 // Add time!
                                        spawnExplosionParticles(Point3D(target.x, 25.0f, target.z), 0xFFFF007F, 35)
                                    }
                                }
                            }
                        }

                        val baseShotMultiplier = when (currentMode) {
                            GameMode.FREE_KICK -> 150
                            GameMode.TARGETS -> 100
                            GameMode.PENALTY -> 80
                        }

                        val pointsEarned = baseShotMultiplier + (comboStreak * 20) + hitTargetsPoints
                        score += pointsEarned
                        coinsEarnedInSession += coinsForThisGoal

                        showGoalGraphic = true
                        triggerHapticFeedback()

                        // Trigger visual goals sparks
                        val goalSparkColor = currentSkin?.particleColor ?: 0xFFFFFF00
                        spawnExplosionParticles(ballPos, goalSparkColor, 40)

                        if (targetHitAlert.isNotEmpty()) {
                            triggerFloatingText(targetHitAlert)
                        } else {
                            triggerFloatingText("هدف! +$coinsForThisGoal عملة")
                        }

                        // Save stats directly to database
                        updateDatabaseUserStats(pointsEarned, coinsForThisGoal, true)
                    } else {
                        // MISSED OVER OR SIDEWAYS
                        triggerMissAction()
                    }
                }

                // 6. Max Bounds / Timeout check
                if (ballPos.y > 27.5f) {
                    if (ballState == BallState.KICKED || ballState == BallState.POST_BOUNCE) {
                        triggerMissAction()
                    }
                    active = false
                }

                // Ball rolling off / completely stopped
                if (ballState != BallState.STILL && ballVel.x * ballVel.x + ballVel.y * ballVel.y + ballVel.z * ballVel.z < 0.05f) {
                    if (ballState == BallState.POST_BOUNCE || ballState == BallState.SAVED) {
                        triggerMissAction()
                    }
                    active = false
                }

                // Stop loop if ball goes out of logic range
                if (ballPos.y < -1f || ballPos.x < -12f || ballPos.x > 12f || ballPos.z > 8f) {
                    active = false
                }
            }

            // Keep updating particles for some time even after ball is dead
            var deadTicks = 0
            while (deadTicks < 30 && particles.isNotEmpty()) {
                delay(16)
                deadTicks++
                updateParticles()
            }
        }
    }

    private fun triggerMissAction() {
        if (ballState == BallState.KICKED || ballState == BallState.POST_BOUNCE) {
            ballState = BallState.OUT_OF_BOUNDS
            comboStreak = 0
            showMissGraphic = true
            triggerHapticFeedback()
            updateDatabaseUserStats(0, 0, false)
        }
    }

    private fun updateDatabaseUserStats(points: Int, coinsWon: Int, isGoal: Boolean) {
        viewModelScope.launch {
            val stats = repository.getInitialStatsOrInsert()
            val newCoins = stats.coins + coinsWon
            val newTotalShots = stats.totalShots + 1
            val newTotalGoals = stats.totalGoals + (if (isGoal) 1 else 0)
            val newHighScore = max(stats.highScore, score)

            val updated = stats.copy(
                highScore = newHighScore,
                coins = newCoins,
                totalShots = newTotalShots,
                totalGoals = newTotalGoals
            )
            repository.saveUserStats(updated)
        }
    }

    private fun triggerFloatingText(message: String) {
        floatMessage = message
        showFloatMessage = true
        viewModelScope.launch {
            delay(1800)
            showFloatMessage = false
        }
    }

    // Purchase skin
    fun purchaseSkin(skin: BallSkin): Boolean {
        val stats = userStats.value ?: return false
        if (stats.coins >= skin.price && !stats.unlockedBalls.contains(skin.id)) {
            val updatedUnlocked = "${stats.unlockedBalls},${skin.id}"
            val updatedCoins = stats.coins - skin.price
            viewModelScope.launch {
                repository.saveUserStats(
                    stats.copy(
                        unlockedBalls = updatedUnlocked,
                        coins = updatedCoins,
                        selectedBall = skin.id
                    )
                )
            }
            triggerFloatingText("تم الشراء والتركيب! ⚽")
            return true
        }
        return false
    }

    // Select skin
    fun selectSkin(skin: BallSkin) {
        val stats = userStats.value ?: return
        if (stats.unlockedBalls.contains(skin.id)) {
            viewModelScope.launch {
                repository.saveUserStats(stats.copy(selectedBall = skin.id))
            }
            triggerFloatingText("تم تركيب ${skin.nameAr}")
        }
    }

    private fun emitTrailParticles() {
        val currentSelected = userStats.value?.selectedBall ?: "classic"
        val skin = ballSkins.find { it.id == currentSelected } ?: return
        val pColor = skin.particleColor ?: return // if no color, don't emit trail

        // Emit 1-2 particles per frame
        particles.add(
            GameParticle(
                x = ballPos.x + (Random.nextFloat() * 0.1f - 0.05f),
                y = ballPos.y,
                z = ballPos.z + (Random.nextFloat() * 0.1f - 0.05f),
                vx = -ballVel.x * 0.2f + (Random.nextFloat() * 0.4f - 0.2f),
                vy = -ballVel.y * 0.1f, // trail lays back
                vz = -ballVel.z * 0.2f + (Random.nextFloat() * 0.4f - 0.2f),
                colorHex = pColor,
                maxSize = 8f + Random.nextFloat() * 8f,
                size = 8f + Random.nextFloat() * 8f,
                alpha = 0.9f
            )
        )
    }

    private fun spawnExplosionParticles(pos: Point3D, colorHex: Long, count: Int) {
        for (i in 0 until count) {
            val speedFactor = 5f
            particles.add(
                GameParticle(
                    x = pos.x,
                    y = pos.y,
                    z = pos.z,
                    vx = (Random.nextFloat() * 2f - 1f) * speedFactor,
                    vy = (Random.nextFloat() * -1.5f + 0.3f) * speedFactor, // burst backwards and sides
                    vz = (Random.nextFloat() * 2f - 1f) * speedFactor + 2f,
                    colorHex = colorHex,
                    maxSize = 10f + Random.nextFloat() * 12f,
                    size = 10f + Random.nextFloat() * 12f,
                    alpha = 1.0f,
                    maxAge = 35 + Random.nextInt(20)
                )
            )
        }
    }

    private fun updateParticles() {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.age++
            if (p.age >= p.maxAge) {
                iterator.remove()
                continue
            }

            // Gravity affects heavy debris
            p.vz += -1.5f * 0.016f

            // Move particle
            p.x += p.vx * 0.016f
            p.y += p.vy * 0.016f
            p.z += p.vz * 0.016f

            // shrink over age
            val ratio = p.age.toFloat() / p.maxAge.toFloat()
            p.size = p.maxSize * (1f - ratio)
            p.alpha = 1f - ratio
        }
    }

    private fun triggerHapticFeedback() {
        // Can be hooked up via Compose HapticFeedback or android Vibrator
    }

    fun saveMatchSessionIfNeeded() {
        if (matchShots > 0) {
            val currentModeStr = when (currentMode) {
                GameMode.PENALTY -> "ركلات جزاء"
                GameMode.FREE_KICK -> "ركلات حرة"
                GameMode.TARGETS -> "رالي الأهداف"
            }
            val rate = if (matchShots > 0) (matchGoals.toFloat() / matchShots.toFloat()) * 100f else 0f
            val log = MatchHistoryEntity(
                mode = currentModeStr,
                goalsScored = matchGoals,
                totalShots = matchShots,
                successRate = rate,
                coinsEarned = coinsEarnedInSession
            )
            viewModelScope.launch {
                repository.addMatchLog(log)
            }
            // Reset counters for the next potential session
            matchShots = 0
            matchGoals = 0
            coinsEarnedInSession = 0
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveMatchSessionIfNeeded()
        gameLoopJob?.cancel()
        timerJob?.cancel()
    }
}
