package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.database.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(viewModel: GameViewModel) {
    val userStats by viewModel.userStats.collectAsState()
    val matchHistory by viewModel.matchHistory.collectAsState()

    var showShopSheet by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Drag-gesture tracking for swiping the ball
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPoints = remember { mutableStateListOf<Offset>() }
    var dragStartTime by remember { mutableStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B19)) // deep obsidian/night sky background
    ) {

        // --- 1. Immersive 3D Stadium Canvas ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (viewModel.ballState == BallState.STILL) {
                                dragStart = offset
                                dragEnd = offset
                                isDragging = true
                                dragPoints.clear()
                                dragPoints.add(offset)
                                dragStartTime = System.currentTimeMillis()
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (isDragging && viewModel.ballState == BallState.STILL) {
                                dragEnd = change.position
                                dragPoints.add(change.position)
                            }
                        },
                        onDragEnd = {
                            if (isDragging && viewModel.ballState == BallState.STILL) {
                                isDragging = false
                                val durationMs = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(50L)
                                val durationSec = durationMs / 1000f

                                // Swipe path analysis
                                val totalDragY = dragStart.y - dragEnd.y // drag up is positive
                                val totalDragX = dragEnd.x - dragStart.x

                                if (totalDragY > 40f) { // Require a minimum drag threshold
                                    // Calculate velocity vectors
                                    // Map screen density units to m/s
                                    val speedMultiplier = 0.07f
                                    val vy = (totalDragY * speedMultiplier / durationSec.coerceAtLeast(0.1f)).coerceIn(18f, 36f)
                                    val vx = (totalDragX * speedMultiplier / durationSec.coerceAtLeast(0.1f)).coerceIn(-11f, 11f)
                                    
                                    // Height lift mapping
                                    val maxSwipeWidth = dragPoints.maxOfOrNull { it.x } ?: dragEnd.x
                                    val minSwipeWidth = dragPoints.minOfOrNull { it.x } ?: dragStart.x
                                    val vz = (totalDragY * 0.024f).coerceIn(2.5f, 10.5f)

                                    // Curve / Magnus spin calculation based on swipe curvature
                                    // We check standard horizontal deviation from absolute line between dragStart and dragEnd
                                    var maxCurveDeviation = 0f
                                    if (dragPoints.size > 2) {
                                        val lineVectorX = dragEnd.x - dragStart.x
                                        val lineVectorY = dragEnd.y - dragStart.y
                                        val lineLength = sqrt(lineVectorX.pow(2) + lineVectorY.pow(2))
                                        
                                        if (lineLength > 10f) {
                                            for (pt in dragPoints) {
                                                // Distance of point pt from line
                                                val num = abs(lineVectorX * (dragStart.y - pt.y) - (dragStart.x - pt.x) * lineVectorY)
                                                val dist = num / lineLength
                                                // Determine hand curve sign (direction vector outer product)
                                                val crossProduct = lineVectorX * (pt.y - dragStart.y) - lineVectorY * (pt.x - dragStart.x)
                                                if (dist > abs(maxCurveDeviation)) {
                                                    maxCurveDeviation = dist * (if (crossProduct > 0) 1f else -1f)
                                                }
                                            }
                                        }
                                    }

                                    // Map the curvature to physical side spins (spinY)
                                    val spinY = (maxCurveDeviation * 0.12f).coerceIn(-12f, 12f)
                                    // Add top spin based on vertical speed variation
                                    val spinX = ((totalDragY / durationMs) * 1.5f).coerceIn(-4f, 4f)

                                    // Kick!
                                    viewModel.kickBall(vx, vy, vz, spinX, spinY)
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                }
        ) {
            val currentSelectedSkin = userStats?.selectedBall ?: "classic"
            val skin = viewModel.ballSkins.find { it.id == currentSelectedSkin } ?: viewModel.ballSkins[0]

            Canvas(modifier = Modifier.fillMaxSize()) {
                val W = size.width
                val H = size.height

                // 1. Draw Sky backdrop (Cosmic stadium light beam sky)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF040612), Color(0xFF0A132C), Color(0xFF003010)),
                        startY = 0f,
                        endY = H * 0.6f
                    )
                )

                // Draw Stars overlay
                val starRandom = Random(12435)
                for (i in 0..40) {
                    val starX = starRandom.nextFloat() * W
                    val starY = starRandom.nextFloat() * H * 0.38f
                    val starAlpha = starRandom.nextFloat() * 0.8f + 0.2f
                    drawCircle(
                        color = Color.White.copy(alpha = starAlpha),
                        radius = starRandom.nextFloat() * 2f + 1f,
                        center = Offset(starX, starY)
                    )
                }

                // Draw Floodlight Gantries at Top-Left and Top-Right Corners
                drawFloodlights(W, H)

                // 2. 3D projection mathematical context
                val camX = 0f
                val camY = -6.0f
                val camZ = 3.2f
                val camDepth = 30.0f

                fun project(pt3D: Point3D): Offset {
                    val distY = pt3D.y - camY
                    val scale = camDepth / (camDepth + distY)
                    
                    val xPix = (W / 2f) + (pt3D.x - camX) * scale * (W * 0.052f)
                    
                    val horizonY = H * 0.39f // High perspective horizon
                    val groundBaseY = H * 0.88f // Lawn bottom y=0 point
                    
                    val grassY = horizonY + (groundBaseY - horizonY) * scale
                    val altPix = pt3D.z * scale * (H * 0.065f)
                    val yPix = grassY - altPix
                    return Offset(xPix, yPix)
                }

                // Helper to scale sizes in 3D projection
                fun scale3D(sizeDp: Dp, y: Float): Float {
                    val distY = y - camY
                    val scale = camDepth / (camDepth + distY)
                    return sizeDp.toPx() * scale
                }

                // 3. Draw Grass and turf lines (Alternating light/dark green stripes in depth-perspective)
                val stripeCount = 10
                val stripeDepth = 26f / stripeCount
                for (i in 0 until stripeCount) {
                    val y1 = i * stripeDepth
                    val y2 = (i + 1) * stripeDepth
                    
                    val ptLeftStart = Point3D(-18f, y1, 0f)
                    val ptRightStart = Point3D(18f, y1, 0f)
                    val ptRightEnd = Point3D(18f, y2, 0f)
                    val ptLeftEnd = Point3D(-18f, y2, 0f)

                    val p1 = project(ptLeftStart)
                    val p2 = project(ptRightStart)
                    val p3 = project(ptRightEnd)
                    val p4 = project(ptLeftEnd)

                    val stripePath = Path().apply {
                        moveTo(p1.x, p1.y)
                        lineTo(p2.x, p2.y)
                        lineTo(p3.x, p3.y)
                        lineTo(p4.x, p4.y)
                        close()
                    }

                    // Alternate colors
                    val grassColor = if (i % 2 == 0) Color(0xFF1B5120) else Color(0xFF236128)
                    drawPath(path = stripePath, color = grassColor)
                }

                // Out of bounds background crowd boards setup
                val ptPitchCornerL = project(Point3D(-14f, 25f, 0f))
                val ptPitchCornerR = project(Point3D(14f, 25f, 0f))

                // Arena/Crowd dark glow bars just behind the pitch
                drawRect(
                    color = Color(0xFF030A12),
                    topLeft = Offset(0f, ptPitchCornerL.y - 40f),
                    size = Size(W, 40f)
                )

                // 4. White Tactical Pitch Markings
                // Goal line
                drawLine(
                    color = Color.White.copy(alpha = 0.55f),
                    start = project(Point3D(-12f, 25f, 0f)),
                    end = project(Point3D(12f, 25f, 0f)),
                    strokeWidth = 6.dp.toPx()
                )

                // Penalty Box Area: spans X from -5.5m to 5.5m, depth from Y = 17m to 25m
                val penLine1 = project(Point3D(-6.0f, 17f, 0f))
                val penLine2 = project(Point3D(6.0f, 17f, 0f))
                val penLine3 = project(Point3D(6.0f, 25f, 0f))
                val penLine4 = project(Point3D(-6.0f, 25f, 0f))

                val penBoxPath = Path().apply {
                    moveTo(penLine4.x, penLine4.y)
                    lineTo(ptPitchCornerL.x + (penLine1.x - ptPitchCornerL.x), penLine1.y)
                    lineTo(penLine2.x, penLine2.y)
                    lineTo(penLine3.x, penLine3.y)
                }
                drawPath(path = penBoxPath, color = Color.White.copy(alpha = 0.35f), style = Stroke(width = 4.dp.toPx()))

                // Penalty box center dot
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f),
                    radius = 5.dp.toPx(),
                    center = project(Point3D(0f, 13.5f, 0f))
                )

                // 5. Draw Target Boards in goal (Targets Mode only)
                if (viewModel.currentMode == GameMode.TARGETS) {
                    for (target in viewModel.targets) {
                        if (target.active) {
                            val projTarget = project(Point3D(target.x, 24.95f, target.z))
                            val targetRad = scale3D(target.radius.dp * 25f, 24.95f)

                            // Inner & outer neon target rings
                            drawCircle(
                                color = Color(0xFFFF006F),
                                radius = targetRad,
                                center = projTarget,
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawCircle(
                                color = Color.White,
                                radius = targetRad * 0.6f,
                                center = projTarget,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            drawCircle(
                                color = Color(0xFFFFD600),
                                radius = targetRad * 0.25f,
                                center = projTarget
                            )
                        }
                    }
                }

                // 6. Draw Goal Net structure sorting mesh
                drawGoalWebNet(W, H, ::project, ::scale3D)

                // 7. Draw Defensive Wall (Free Kick Mode)
                if (viewModel.currentMode == GameMode.FREE_KICK) {
                    val halfWallWidth = (viewModel.wallDefenderCount * viewModel.wallDefenderWidth) / 2f
                    // Draw 3 defenders side by side standing
                    for (i in 0 until viewModel.wallDefenderCount) {
                        val defX = (viewModel.wallX - halfWallWidth) + (i * viewModel.wallDefenderWidth) + (viewModel.wallDefenderWidth / 2f)
                        val defY = viewModel.wallY
                        val defHeight = 1.85f // standard footballer height
                        
                        val basePt = project(Point3D(defX, defY, 0f))
                        val topPt = project(Point3D(defX, defY, defHeight + viewModel.wallJumpOffset))
                        
                        val playerWidth = scale3D(35.dp, defY)
                        val playerHeight = basePt.y - topPt.y

                        // Draw stylized defender body jersey
                        if (playerHeight > 0) {
                            // Leg Cleats
                            drawLine(
                                color = Color.Black,
                                start = basePt,
                                end = Offset(basePt.x, basePt.y - playerHeight * 0.2f),
                                strokeWidth = playerWidth * 0.3f
                            )

                            // Shorts and torso
                            val bodyRect = Rect(
                                left = basePt.x - playerWidth * 0.45f,
                                top = topPt.y + playerHeight * 0.2f,
                                right = basePt.x + playerWidth * 0.45f,
                                bottom = basePt.y - playerHeight * 0.2f
                            )
                            drawRoundRect(
                                color = Color(0xFF1E3A8A), // Blue away team jersey
                                topLeft = Offset(bodyRect.left, bodyRect.top),
                                size = Size(bodyRect.width, bodyRect.height),
                                cornerRadius = CornerRadius(12f, 12f)
                            )

                            // Defender shoulder/heads
                            drawCircle(
                                color = Color(0xFFF3A58E), // skins tone
                                radius = playerWidth * 0.3f,
                                center = Offset(basePt.x, topPt.y + playerHeight * 0.15f)
                            )
                            
                            // Yellow Hair crown
                            drawCircle(
                                color = Color(0xFFF59E0B),
                                radius = playerWidth * 0.22f,
                                center = Offset(basePt.x, topPt.y + playerHeight * 0.08f)
                            )

                            // Protective defensive arms crossed
                            drawLine(
                                color = Color(0xFF1E3A8A),
                                start = Offset(basePt.x - playerWidth * 0.48f, topPt.y + playerHeight * 0.4f),
                                end = Offset(basePt.x + playerWidth * 0.48f, topPt.y + playerHeight * 0.4f),
                                strokeWidth = playerWidth * 0.18f
                            )
                        }
                    }
                }

                // 8. Draw Goalkeeper
                val ptGk = project(viewModel.gkPos)
                val gkH = scale3D(52.dp, viewModel.gkPos.y)
                val gkW = scale3D(32.dp, viewModel.gkPos.y)

                drawActiveGoalkeeper(ptGk, gkW, gkH, viewModel.gkState)

                // 9. Draw Flight Shadow of the ball on Grass platform (Z=0)
                if (viewModel.ballState != BallState.STILL) {
                    val shadowCenter = project(Point3D(viewModel.ballPos.x, viewModel.ballPos.y, 0.08f))
                    // Shadow radius is inverse to height Z
                    val shadowScale = (3f / (3f + viewModel.ballPos.z)).coerceIn(0.1f, 1.0f)
                    val baseShadowRad = scale3D(18.dp, viewModel.ballPos.y) * shadowScale

                    drawOval(
                        color = Color.Black.copy(alpha = 0.42f * shadowScale),
                        topLeft = Offset(shadowCenter.x - baseShadowRad * 1.5f, shadowCenter.y - baseShadowRad * 0.6f),
                        size = Size(baseShadowRad * 3f, baseShadowRad * 1.2f)
                    )
                }

                // 10. Draw Particles
                for (p in viewModel.particles) {
                    val projP = project(Point3D(p.x, p.y, p.z))
                    val pScaleSize = scale3D(p.size.dp, p.y)

                    if (pScaleSize > 0) {
                        drawCircle(
                            color = Color(p.colorHex).copy(alpha = p.alpha),
                            radius = pScaleSize,
                            center = projP
                        )
                    }
                }

                // 11. Draw the actual Soccer Ball!
                val ptBall = project(viewModel.ballPos)
                // Base diameter of soccer ball is ~22cm in meters, scale appropriately
                val ballRadius = scale3D(20.dp, viewModel.ballPos.y)

                if (ballRadius > 0.5f) {
                    // Draw Sphere Shadow glow
                    val ballPaint = Paint().asFrameworkPaint()

                    // Main color circle
                    drawCircle(
                        color = Color(skin.primaryColor),
                        radius = ballRadius,
                        center = ptBall
                    )

                    // Draw Pentagonal Soccer patterns (moving with spin angles to give rotating 3D effect!)
                    // Generate rotation angle over time when ball is flying
                    val spinRotAngle = if (viewModel.ballState != BallState.STILL) {
                        (System.currentTimeMillis() / 25f) % 360f
                    } else 0f

                    drawSoccerPanels(ptBall, ballRadius, Color(skin.secondaryColor), spinRotAngle)

                    // Draw glossy gradient 3D highlight (radial glow top-left)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.4f), Color.Transparent),
                            center = Offset(ptBall.x - ballRadius * 0.3f, ptBall.y - ballRadius * 0.3f),
                            radius = ballRadius * 1.1f
                        ),
                        radius = ballRadius,
                        center = ptBall
                    )

                    // Outer stroke border
                    drawCircle(
                        color = Color.DarkGray.copy(alpha = 0.35f),
                        radius = ballRadius,
                        center = ptBall,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // 12. Draw Dragger-Aim Indicator (Aim arrow when draging or swiping)
                if (isDragging && viewModel.ballState == BallState.STILL) {
                    val dragPowerRatio = ((dragStart.y - dragEnd.y) / H).coerceIn(0.02f, 0.45f)
                    val ptAim3DStart = Point3D(viewModel.ballPos.x, viewModel.ballPos.y, viewModel.ballPos.z)
                    
                    // Predict trajectory
                    val aimXDelta = (dragEnd.x - dragStart.x) * -0.05f
                    val aimYLength = (dragStart.y - dragEnd.y) * 0.15f
                    val ptAim3DEnd = Point3D(
                        (viewModel.ballPos.x + aimXDelta).coerceIn(-6f, 6f),
                        viewModel.ballPos.y + aimYLength.coerceAtLeast(4f),
                        (viewModel.ballPos.z + aimYLength * 0.16f).coerceIn(0.5f, 2.8f)
                    )

                    val screenStart = project(ptAim3DStart)
                    val screenEnd = project(ptAim3DEnd)

                    // Draw neon aiming guide arrow
                    drawLine(
                        color = Color(0xFF00FFCC).copy(alpha = 0.85f),
                        start = screenStart,
                        end = screenEnd,
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Dotted sub segments
                    for (i in 1..4) {
                        val fraction = i / 4f
                        val midX = viewModel.ballPos.x + (ptAim3DEnd.x - viewModel.ballPos.x) * fraction
                        val midY = viewModel.ballPos.y + (ptAim3DEnd.y - viewModel.ballPos.y) * fraction
                        val midZ = viewModel.ballPos.z + (ptAim3DEnd.z - viewModel.ballPos.z) * fraction
                        val midProjected = project(Point3D(midX, midY, midZ))

                        drawCircle(
                            color = Color(0xFFFFD600),
                            radius = 6.dp.toPx() * (1f - fraction * 0.5f),
                            center = midProjected
                        )
                    }
                }
            }
        }

        // --- 2. Floating Header Status HUD ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xD90B132B)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .shadow(12.dp, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Score Tracker
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = "النقاط",
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray, fontFamily = FontFamily.SansSerif)
                        )
                        Text(
                            text = "${viewModel.score}",
                            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00FFCC), fontFamily = FontFamily.SansSerif)
                        )
                    }

                    // Mode title badge
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val badgeText = when (viewModel.currentMode) {
                            GameMode.PENALTY -> "ضربات جزاء"
                            GameMode.FREE_KICK -> "ركلات حرة"
                            GameMode.TARGETS -> "الأهداف السريعة"
                        }
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.border(1.dp, Color(0xFF00FFCC), RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = badgeText,
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.SansSerif),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        
                        // Remaining Timer (Targets Mode)
                        if (viewModel.currentMode == GameMode.TARGETS) {
                            Text(
                                text = "الوقت: ${viewModel.targetTimerSeconds}ث",
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (viewModel.targetTimerSeconds < 10) Color.Red else Color(0xFFFFD600),
                                    fontFamily = FontFamily.SansSerif
                                ),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    // Gold Coins and Streak
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${userStats?.coins ?: 0}",
                                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFFFFD600), fontFamily = FontFamily.SansSerif)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Coins",
                                tint = Color(0xFFFFD600),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (viewModel.comboStreak > 0) {
                            Surface(
                                color = Color(0xFFFF3F6C),
                                shape = CircleShape,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = "متتالية x${viewModel.comboStreak}",
                                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = FontFamily.SansSerif),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 3. Left Control Panel Floating Buttons (In-Stadium HUD controls) ---
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Help/Instructions
            FloatingActionButton(
                onClick = { showHelpDialog = true },
                containerColor = Color(0xD90F172A),
                contentColor = Color.White,
                modifier = Modifier
                    .size(46.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .testTag("help_button")
            ) {
                Icon(imageVector = Icons.Default.Info, contentDescription = "Instructions", modifier = Modifier.size(20.dp))
            }

            // Mode Selector Wheel
            FloatingActionButton(
                onClick = {
                    val nextMode = when (viewModel.currentMode) {
                        GameMode.PENALTY -> GameMode.FREE_KICK
                        GameMode.FREE_KICK -> GameMode.TARGETS
                        GameMode.TARGETS -> GameMode.PENALTY
                    }
                    viewModel.selectMode(nextMode)
                },
                containerColor = Color(0xD90F172A),
                contentColor = Color(0xFF00FFCC),
                modifier = Modifier
                    .size(46.dp)
                    .border(1.dp, Color(0xFF00FFCC).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .testTag("next_mode_button")
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Modes")
            }

            // Shop skins trigger
            FloatingActionButton(
                onClick = { showShopSheet = true },
                containerColor = Color(0xD90F172A),
                contentColor = Color(0xFFFFD600),
                modifier = Modifier
                    .size(46.dp)
                    .border(1.dp, Color(0xFFFFD600).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .testTag("shop_button")
            ) {
                Icon(imageVector = Icons.Default.ShoppingCart, contentDescription = "Skins Shop")
            }

            // Stats log trigger
            FloatingActionButton(
                onClick = { showStatsDialog = true },
                containerColor = Color(0xD90F172A),
                contentColor = Color.White,
                modifier = Modifier
                    .size(46.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .testTag("stats_button")
            ) {
                Icon(imageVector = Icons.Default.List, contentDescription = "Match logs", modifier = Modifier.size(20.dp))
            }

            // Sound on/off
            FloatingActionButton(
                onClick = { viewModel.muteToggle() },
                containerColor = Color(0xD90F172A),
                contentColor = Color.White,
                modifier = Modifier
                    .size(46.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .testTag("sound_button")
            ) {
                Icon(
                    imageVector = if (viewModel.isMuted) Icons.Default.PlayArrow else Icons.Default.Clear,
                    contentDescription = "Mute"
                )
            }
        }

        // --- 4. Bottom Reset controller ---
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { viewModel.restartGame() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xE6E11D48)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .shadow(8.dp)
                    .testTag("restart_button")
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reset")
                Spacer(modifier = Modifier.width(8.dp))
                Text("إعادة تصفير اللعب", style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp))
            }

            if (viewModel.ballState != BallState.STILL) {
                Button(
                    onClick = { viewModel.setupGame() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xE6059669)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .shadow(8.dp)
                        .testTag("reset_shoot_button")
                ) {
                    Text("التسديدة التالية", style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                }
            }
        }

        // Guide tip at first entrance
        if (viewModel.ballState == BallState.STILL && !isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = " اسحب الكرة للأعلى للتسديد، واثنِ سحبك لتحقيق ركلة لولبية (موزية)! ⚽",
                    style = TextStyle(fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center, fontFamily = FontFamily.SansSerif)
                )
            }
        }

        // --- 5. Floating "+50 Coins" animation alert ---
        AnimatedVisibility(
            visible = viewModel.showFloatMessage,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color(0xFFFFD600),
                shape = RoundedCornerShape(50),
                modifier = Modifier.shadow(16.dp)
            ) {
                Text(
                    text = viewModel.floatMessage,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color(0xFF040612), fontFamily = FontFamily.SansSerif),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }

        // --- 6. Goal / Save HUD Animation Overlays ---
        GoalOverlay(visible = viewModel.showGoalGraphic, comboText = viewModel.comboMessage) {
            viewModel.setupGame()
        }
        SaveOverlay(visible = viewModel.showSaveGraphic) {
            viewModel.setupGame()
        }
        MissOverlay(visible = viewModel.showMissGraphic) {
            viewModel.setupGame()
        }

        // --- 7. Sidebar Store Bottom Sheet ---
        if (showShopSheet) {
            ModalBottomSheet(
                onDismissRequest = { showShopSheet = false },
                containerColor = Color(0xFF0F172A),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 40.dp)
                        .padding(horizontal = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "متجر كرات النيون المميزة",
                            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFD600), fontFamily = FontFamily.SansSerif)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${userStats?.coins ?: 0}",
                                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFD600), fontFamily = FontFamily.SansSerif)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(imageVector = Icons.Default.Star, contentDescription = "Gold coins", tint = Color(0xFFFFD600))
                        }
                    }
                    Divider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 12.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxHeight(0.6f)
                    ) {
                        items(viewModel.ballSkins) { skinItem ->
                            val isUnlocked = userStats?.unlockedBalls?.contains(skinItem.id) == true
                            val isSelected = userStats?.selectedBall == skinItem.id

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF1E293B) else Color(0xFF1E293B).copy(alpha = 0.5f)
                                ),
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF00FFCC) else Color.White.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isUnlocked) {
                                            viewModel.selectSkin(skinItem)
                                        } else {
                                            viewModel.purchaseSkin(skinItem)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Colored Ball Representation Dot
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(Color(skinItem.primaryColor), CircleShape)
                                                .border(2.dp, Color(skinItem.secondaryColor), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = skinItem.nameAr,
                                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.SansSerif)
                                            )
                                            Text(
                                                text = skinItem.descriptionAr,
                                                style = TextStyle(fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.SansSerif)
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (isUnlocked) {
                                                viewModel.selectSkin(skinItem)
                                            } else {
                                                viewModel.purchaseSkin(skinItem)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = when {
                                                isSelected -> Color(0xFF059669)
                                                isUnlocked -> Color(0xFF475569)
                                                else -> Color(0xFFFFD600)
                                            }
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = when {
                                                isSelected -> "مجهّزة"
                                                isUnlocked -> "تجهيز"
                                                else -> "${skinItem.price} 🪙"
                                            },
                                            style = TextStyle(
                                                fontFamily = FontFamily.SansSerif,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (isUnlocked || isSelected) Color.White else Color.Black
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 8. Help & How to Play Dialog ---
        if (showHelpDialog) {
            Dialog(onDismissRequest = { showHelpDialog = false }) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "كيف تلعب 3D Football؟",
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFF00FFCC), fontFamily = FontFamily.SansSerif)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "لعبة ركلات حركية ثلاثية الأبعاد بملعب تفاعلي مميز:\n\n" +
                                    "1. ⚡ التسديد: ضع إصبعك على الطابة بالأسفل واسحبها للأعلى بسرعة. سرعة السحب تحدد فورة وقوة الركلة!\n\n" +
                                    "2. 🔄 ركلة موزة (قوس لولبي): اسحب إصبعك بمسار قوسي (منحنٍ) لليسار أو اليمين أثناء السحب لإضفاء تأثير دوران إيروديناميكي يلف وينيخ بالهواء من فوق الحائط الدفاعي!\n\n" +
                                    "3. 🛡️ التحديات ركلة حرة: سيتطلب منك لف الكرة حول حائط المدافعين البشريين الذين يقفزون لتصدي كرتك.\n\n" +
                                    "4. 🎯 رالي الأهداف: في هذا التحدي الزمني، ركز تسديدك في اللوحات الدائرية بطلب نقاط مضاعفة وإضافة ثوانٍ للعداد!",
                            style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Right, lineHeight = 20.sp, fontFamily = FontFamily.SansSerif)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { showHelpDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("فهمت! دعني ألعب", style = TextStyle(color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif))
                        }
                    }
                }
            }
        }

        // --- 9. Stats / Match History Logs Dialog ---
        if (showStatsDialog) {
            Dialog(onDismissRequest = { showStatsDialog = false }) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(18.dp)
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "أرشيف وسجل نتائجك 🏆",
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFFFFD600), fontFamily = FontFamily.SansSerif)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Overall records summary
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("أعلى متتالية نقاظ", style = TextStyle(fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.SansSerif))
                                Text("${userStats?.highScore ?: 0}", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.SansSerif))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("الركلات الكلية", style = TextStyle(fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.SansSerif))
                                Text("${userStats?.totalShots ?: 0}", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.SansSerif))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("الأهداف المسجلة", style = TextStyle(fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.SansSerif))
                                Text("${userStats?.totalGoals ?: 0}", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981), fontFamily = FontFamily.SansSerif))
                            }
                        }

                        Divider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 12.dp))

                        if (matchHistory.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "لا توجد مباريات مسجلة بعد. ابدأ وسدد أول كراتك الحية!",
                                    style = TextStyle(fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center, fontFamily = FontFamily.SansSerif)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(matchHistory) { historyItem ->
                                    Surface(
                                        color = Color(0xFF1E293B).copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = historyItem.mode,
                                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.SansSerif)
                                                )
                                                Text(
                                                    text = "نسبة النجاح: ${historyItem.successRate.toInt()}%",
                                                    style = TextStyle(fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.SansSerif)
                                                )
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "سجلت: ${historyItem.goalsScored}/${historyItem.totalShots}",
                                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF00FFCC), fontFamily = FontFamily.SansSerif)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    color = Color(0xFFFFD600).copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "+${historyItem.coinsEarned} 🪙",
                                                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD600), fontFamily = FontFamily.SansSerif),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { viewModel.clearAllLogs() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f), contentColor = Color.Red),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("تصفية الأرشيف", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif))
                        }
                    }
                }
            }
        }
    }
}

// Draw Goal Web Net
private fun DrawScope.drawGoalWebNet(
    W: Float,
    H: Float,
    project: (Point3D) -> Offset,
    scale3D: (Dp, Float) -> Float
) {
    // Top-Left, Top-Right, Bottom-Left, Bottom-Right limits of Goal Posts
    // Goal line: -3.6m to 3.6m at Y=25.0
    // Crossbar at Z=2.44m
    val postL = Point3D(-3.6f, 25.0f, 0f)
    val postR = Point3D(3.6f, 25.0f, 0f)
    val barL = Point3D(-3.6f, 25.0f, 2.44f)
    val barR = Point3D(3.6f, 25.0f, 2.44f)

    // Goal mesh back frame
    val netBackL = Point3D(-3.6f, 26.1f, 0f)
    val netBackR = Point3D(3.6f, 26.1f, 0f)
    val netBackTopL = Point3D(-3.6f, 26.1f, 2.44f)
    val netBackTopR = Point3D(3.6f, 26.1f, 2.44f)

    val projPostL = project(postL)
    val projPostR = project(postR)
    val projBarL = project(barL)
    val projBarR = project(barR)

    val projNetBackL = project(netBackL)
    val projNetBackR = project(netBackR)
    val projNetBackTopL = project(netBackTopL)
    val projNetBackTopR = project(netBackTopR)

    val scaledSecWidth = scale3D(10.dp, 25f)

    // 1. Draw Net mesh background lines
    val pNet = Path()
    // Horizontal rings
    val netRows = 6
    for (r in 0..netRows) {
        val frac = r / netRows.toFloat()
        // Draw horizontal mesh connections from left post to right
        val leftNode = Point3D(-3.6f, 25.0f + 1.1f * frac, 2.44f * frac)
        val rightNode = Point3D(3.6f, 25.0f + 1.1f * frac, 2.44f * frac)
        val pL = project(leftNode)
        val pR = project(rightNode)
        drawLine(
            color = Color.White.copy(alpha = 0.15f),
            start = pL,
            end = pR,
            strokeWidth = 1.dp.toPx()
        )
    }

    val netCols = 18
    for (c in 0..netCols) {
        val frac = (c / netCols.toFloat()) * 2f - 1f // -1 to 1 range
        val topNode = Point3D(3.6f * frac, 25.0f, 2.44f)
        val bottomNode = Point3D(3.6f * frac, 25.0f, 0f)
        
        // Connect vertical mesh lines
        val pT = project(topNode)
        val pB = project(bottomNode)
        
        // draw slightly curving down depth line for net texture
        val pBackT = project(Point3D(3.6f * frac, 26.1f, 2.44f))
        val pBackB = project(Point3D(3.6f * frac, 26.1f, 0f))
        
        drawLine(color = Color.White.copy(alpha = 0.15f), start = pT, end = pBackT, strokeWidth = 1.dp.toPx())
        drawLine(color = Color.White.copy(alpha = 0.15f), start = pBackT, end = pBackB, strokeWidth = 1.dp.toPx())
    }

    // 2. Draw front metal white posts (Left Post, Right Post, Crossbar)
    // Left post vertical bar
    drawLine(
        color = Color.White,
        start = projPostL,
        end = projBarL,
        strokeWidth = scaledSecWidth * 0.9f,
        cap = StrokeCap.Round
    )

    // Right post vertical bar
    drawLine(
        color = Color.White,
        start = projPostR,
        end = projBarR,
        strokeWidth = scaledSecWidth * 0.9f,
        cap = StrokeCap.Round
    )

    // Horizontal crossbar
    drawLine(
        color = Color.White,
        start = projBarL,
        end = projBarR,
        strokeWidth = scaledSecWidth * 0.9f,
        cap = StrokeCap.Round
    )
}

// Draw Goalkeeper rendering
private fun DrawScope.drawActiveGoalkeeper(
    center: Offset,
    gkW: Float,
    gkH: Float,
    state: GoalkeeperState
) {
    val legHeight = gkH * 0.35f
    val bodyHeight = gkH * 0.45f
    val headRadius = gkW * 0.28f

    // Save Canvas graphics context rotation for diving tilt effects
    val tiltAngle = when (state) {
        GoalkeeperState.DIVING_LEFT -> -35f
        GoalkeeperState.DIVING_UP_LEFT -> -55f
        GoalkeeperState.DIVING_RIGHT -> 35f
        GoalkeeperState.DIVING_UP_RIGHT -> 55f
        else -> 0f
    }

    // Wrap drawing with custom diving rotation translation
    // We can simulate tilting offset logically:
    val centerShiftX = when (state) {
        GoalkeeperState.DIVING_LEFT, GoalkeeperState.DIVING_UP_LEFT -> -gkW * 0.5f
        GoalkeeperState.DIVING_RIGHT, GoalkeeperState.DIVING_UP_RIGHT -> gkW * 0.5f
        else -> 0f
    }

    val finalCenter = Offset(center.x + centerShiftX, center.y)

    // Draw shadow of goalkeeper directly beneath them
    drawOval(
        color = Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(center.x - gkW * 0.7f, center.y + gkH * 0.45f),
        size = Size(gkW * 1.4f, gkH * 0.15f)
    )

    // Draw stylized goalkeeper:
    // Jersey (Red-neon team uniform)
    val jerseyColor = if (state == GoalkeeperState.CELEBRATING_SAVE) Color(0xFFFFB300) else Color(0xFFFF1E56)
    val shortsColor = Color(0xFF1E293B)

    // Center/Body Torso
    val torsoTop = finalCenter.y - gkH * 0.2f
    val torsoBottom = finalCenter.y + gkH * 0.15f
    val torsoRect = Rect(finalCenter.x - gkW * 0.35f, torsoTop, finalCenter.x + gkW * 0.35f, torsoBottom)
    drawRoundRect(
        color = jerseyColor,
        topLeft = Offset(torsoRect.left, torsoRect.top),
        size = Size(torsoRect.width, torsoRect.height),
        cornerRadius = CornerRadius(8f, 8f)
    )

    // Team badge '1' on jersey back/front
    drawCircle(
        color = Color.White,
        radius = gkW * 0.12f,
        center = Offset(finalCenter.x, finalCenter.y - gkH * 0.05f)
    )

    // Goalie head
    val headCenter = Offset(finalCenter.x, finalCenter.y - gkH * 0.3f)
    drawCircle(
        color = Color(0xFFFBCFE8), // skin tone
        radius = headRadius,
        center = headCenter
    )
    // Goalie cap/hair
    drawArc(
        color = Color(0xFF334155),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(headCenter.x - headRadius, headCenter.y - headRadius),
        size = Size(headRadius * 2f, headRadius * 2f)
    )

    // Legs Shorts
    val shortsRect = Rect(finalCenter.x - gkW * 0.34f, torsoBottom, finalCenter.x + gkW * 0.34f, torsoBottom + gkH * 0.14f)
    drawRect(
        color = shortsColor,
        topLeft = Offset(shortsRect.left, shortsRect.top),
        size = Size(shortsRect.width, shortsRect.height)
    )

    // Left leg
    drawLine(
        color = Color(0xFFFBCFE8),
        start = Offset(finalCenter.x - gkW * 0.2f, shortsRect.bottom),
        end = Offset(finalCenter.x - gkW * (if (state == GoalkeeperState.STILL) 0.2f else 0.45f), finalCenter.y + gkH * 0.48f),
        strokeWidth = gkW * 0.15f
    )
    // Right leg
    drawLine(
        color = Color(0xFFFBCFE8),
        start = Offset(finalCenter.x + gkW * 0.2f, shortsRect.bottom),
        end = Offset(finalCenter.x + gkW * (if (state == GoalkeeperState.STILL) 0.2f else 0.45f), finalCenter.y + gkH * 0.48f),
        strokeWidth = gkW * 0.15f
    )

    // Keeper specialized big gloves!
    val gloveRadius = gkW * 0.18f
    val armsY = finalCenter.y - gkH * 0.15f
    val armOffsetL = gkW * 0.65f
    val armOffsetR = gkW * 0.65f

    val (armLX, armLY) = when (state) {
        GoalkeeperState.DIVING_LEFT, GoalkeeperState.DIVING_UP_LEFT -> Pair(finalCenter.x - armOffsetL * 1.5f, finalCenter.y - gkH * 0.35f)
        GoalkeeperState.CELEBRATING_SAVE -> Pair(finalCenter.x - armOffsetL * 0.8f, finalCenter.y - gkH * 0.5f)
        else -> Pair(finalCenter.x - armOffsetL, armsY)
    }

    val (armRX, armRY) = when (state) {
        GoalkeeperState.DIVING_RIGHT, GoalkeeperState.DIVING_UP_RIGHT -> Pair(finalCenter.x + armOffsetR * 1.5f, finalCenter.y - gkH * 0.35f)
        GoalkeeperState.CELEBRATING_SAVE -> Pair(finalCenter.x + armOffsetR * 0.8f, finalCenter.y - gkH * 0.5f)
        else -> Pair(finalCenter.x + armOffsetR, armsY)
    }

    // Left Arm sleeve & glove
    drawLine(
        color = jerseyColor,
        start = Offset(finalCenter.x - gkW * 0.25f, torsoTop + gkH * 0.08f),
        end = Offset(armLX, armLY),
        strokeWidth = gkW * 0.13f
    )
    drawCircle(
        color = Color(0xFFFFFF00), // Glowing Neon yellow goalie gloves!
        radius = gloveRadius,
        center = Offset(armLX, armLY)
    )

    // Right Arm sleeve & glove
    drawLine(
        color = jerseyColor,
        start = Offset(finalCenter.x + gkW * 0.25f, torsoTop + gkH * 0.08f),
        end = Offset(armRX, armRY),
        strokeWidth = gkW * 0.13f
    )
    drawCircle(
        color = Color(0xFFFFFF00), // Neon yellow goalie gloves!
        radius = gloveRadius,
        center = Offset(armRX, armRY)
    )
}

// Floodlights drawing
private fun DrawScope.drawFloodlights(W: Float, H: Float) {
    // Top-Left Light
    val lightPostL = Offset(W * 0.08f, H * 0.06f)
    val lightPostR = Offset(W * 0.92f, H * 0.06f)

    // draw glowing backlighting beams (Transparent conics)
    val glowPathL = Path().apply {
        moveTo(lightPostL.x, lightPostL.y)
        lineTo(lightPostL.x - W * 0.15f, H * 0.65f)
        lineTo(lightPostL.x + W * 0.15f, H * 0.65f)
        close()
    }
    
    val glowPathR = Path().apply {
        moveTo(lightPostR.x, lightPostR.y)
        lineTo(lightPostR.x - W * 0.15f, H * 0.65f)
        lineTo(lightPostR.x + W * 0.15f, H * 0.65f)
        close()
    }

    drawPath(
        path = glowPathL,
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF00FFCC).copy(alpha = 0.12f), Color.Transparent),
            center = lightPostL,
            radius = W * 0.5f
        )
    )

    drawPath(
        path = glowPathR,
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF00FFCC).copy(alpha = 0.12f), Color.Transparent),
            center = lightPostR,
            radius = W * 0.5f
        )
    )

    // Draw physical posts lamps
    drawRect(
        color = Color(0xFF475569),
        topLeft = Offset(lightPostL.x - 22f, lightPostL.y - 10f),
        size = Size(44f, 20f)
    )
    drawRect(
        color = Color(0xFF475569),
        topLeft = Offset(lightPostR.x - 22f, lightPostR.y - 10f),
        size = Size(44f, 20f)
    )

    // Glowing lamps bulbs
    drawCircle(color = Color.White, radius = 9f, center = Offset(lightPostL.x - 10f, lightPostL.y))
    drawCircle(color = Color.White, radius = 9f, center = Offset(lightPostL.x + 10f, lightPostL.y))
    drawCircle(color = Color.White, radius = 9f, center = Offset(lightPostR.x - 10f, lightPostR.y))
    drawCircle(color = Color.White, radius = 9f, center = Offset(lightPostR.x + 10f, lightPostR.y))
}

// Draw Soccer pentagonal patterns (simulating spinning core sphere)
private fun DrawScope.drawSoccerPanels(
    center: Offset,
    radius: Float,
    panelColor: Color,
    angle: Float
) {
    val numPanels = 5
    val radAngle = Math.toRadians(angle.toDouble())

    // Scale down panel size
    val pSize = radius * 0.35f

    // Center pentagon
    val cPath = Path().apply {
        for (i in 0 until numPanels) {
            val a = radAngle + i * (2 * Math.PI / numPanels)
            val px = center.x + cos(a).toFloat() * pSize
            val py = center.y + sin(a).toFloat() * pSize
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path = cPath, color = panelColor)

    // Outer edge panel markers for 3D sphere curvature cues
    for (i in 0 until numPanels) {
        val a = radAngle + i * (2 * Math.PI / numPanels)
        val outerCenter = Offset(
            center.x + cos(a).toFloat() * radius * 0.65f,
            center.y + sin(a).toFloat() * radius * 0.65f
        )
        drawCircle(
            color = panelColor,
            radius = radius * 0.18f,
            center = outerCenter
        )
    }
}


// --- SCREEN HUD ANCHORED OVERLAYS ---

@Composable
fun GoalOverlay(visible: Boolean, comboText: String, onAnimationEnd: () -> Unit) {
    if (visible) {
        // Trigger layout delay to auto transition to next shoot
        LaunchedEffect(Unit) {
            delay(2400)
            onAnimationEnd()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "هددددددددف! ⚽🔥",
                    style = TextStyle(
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFFD600),
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center,
                        shadow = Shadow(color = Color.Red, blurRadius = 24f)
                    ),
                    modifier = Modifier.animateContentSize()
                )
                
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = comboText,
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}

@Composable
fun SaveOverlay(visible: Boolean, onAnimationEnd: () -> Unit) {
    if (visible) {
        LaunchedEffect(Unit) {
            delay(2000)
            onAnimationEnd()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "تصدي رائع! 🧤🧤",
                style = TextStyle(
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF00FFCC),
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(color = Color(0xFF0F172A), blurRadius = 15f)
                )
            )
        }
    }
}

@Composable
fun MissOverlay(visible: Boolean, onAnimationEnd: () -> Unit) {
    if (visible) {
        LaunchedEffect(Unit) {
            delay(1800)
            onAnimationEnd()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "خارج المرمى! ❌",
                style = TextStyle(
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFF3F6C),
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
