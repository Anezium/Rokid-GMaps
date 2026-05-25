package com.anezium.rokidgmaps.glasses

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.anezium.rokidgmaps.shared.protocol.StepInfo
import com.anezium.rokidgmaps.shared.protocol.Waypoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TILE_SIZE = 256
        private const val MAP_ZOOM = 16
        private const val MIN_OVERVIEW_ZOOM = 3
        private const val MAX_OVERVIEW_ZOOM = 15
        private const val STATUS_SAFE_BOTTOM = 34f
        private const val SCREEN_SWIPE_DISTANCE_THRESHOLD = 60
        private const val SCREEN_SWIPE_VELOCITY_THRESHOLD = 60
        private const val TEMPLE_PAD_SWIPE_DISTANCE_THRESHOLD = 100
        private const val TEMPLE_PAD_SWIPE_VELOCITY_THRESHOLD = 100
        private const val VIEW_GESTURE_SUPPRESSION_MS = 800L
        private const val TRANSIT_SWIPE_COOLDOWN_MS = 700L
    }

    private data class OverviewProjection(
        val zoom: Int,
        val minWorldX: Double,
        val minWorldY: Double,
        val maxWorldX: Double,
        val maxWorldY: Double,
        val scale: Double,
        val offsetX: Float,
        val offsetY: Float
    )

    private data class TransitRecapEntry(
        val stepIndex: Int,
        val endStepIndex: Int,
        val isTransit: Boolean,
        val lineLabel: String,
        val headsign: String,
        val departure: String,
        val arrival: String,
        val stopCount: Int?,
        val distanceMeters: Double,
        val rawInstruction: String,
        val detailInstructions: List<String>
    )

    private data class ScreenPoint(val x: Float, val y: Float)

    // Monochrome green palette — these glasses only display green
    private val hudBrightGreen = Color.parseColor("#00FF00")
    private val hudGreen = Color.parseColor("#00CC00")
    private val hudDimGreen = Color.parseColor("#008800")
    private val hudDarkGreen = Color.parseColor("#004400")
    private val hudFaintGreen = Color.parseColor("#003300")

    // Green-only color matrix with dark crush: keeps black map pixels off and
    // turns only brighter road/label pixels into green.
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,       // R output = 0
            0.90f, 1.77f, 0.33f, 0f, -52f, // G output = crushed luminance
            0f, 0f, 0f, 0f, 0f,       // B output = 0
            0f, 0f, 0f, 1f, 0f        // A output = alpha
        )))
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; style = Paint.Style.STROKE; strokeWidth = 7f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x66, 0, 0xFF, 0); style = Paint.Style.STROKE; strokeWidth = 18f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; style = Paint.Style.FILL
    }
    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 20f
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 16f
    }
    private val notifTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 15f
    }
    private val notifBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 13f
    }
    private val separatorPaint = Paint().apply {
        color = hudDarkGreen; strokeWidth = 1f
    }
    private val bgPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }
    private val mapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.FILL
    }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val landmarkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 255, 0)
        style = Paint.Style.FILL
    }

    // Turn alert overlay paints (pre-allocated, all green)
    private val turnAlertBgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0); style = Paint.Style.FILL
    }
    private val turnAlertArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; typeface = Typeface.MONOSPACE; textSize = 64f
        textAlign = Paint.Align.CENTER
    }
    private val turnAlertInstrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val turnAlertDistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; typeface = Typeface.MONOSPACE; textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    // Reusable Rect for tile drawing (avoids allocation in draw loop)
    private val tileDestRect = Rect()
    private val statusClockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    var state: HudState = HudState()
        set(value) {
            field = value
            if (!isTransitSurface()) resetTransitInteraction()
            postInvalidate()
        }

    var tileManager: TileManager? = null
    var onLayoutToggle: (() -> Unit)? = null
    var onContentViewToggle: (() -> Unit)? = null
    var onDirectionalSwipe: ((Boolean) -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    private var suppressViewTouchUntilMs = 0L
    private var transitDetailExpanded = false
    private var transitDetailOffset = 0
    private var transitTimelineOffset = 0
    private var lastTransitSwipeAtMs = 0L
    private var lastTransitEntryKey = ""

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onContentViewToggle?.invoke()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val start = e1 ?: return false
            val diffX = e2.x - start.x
            val diffY = e2.y - start.y
            val absX = abs(diffX)
            val absY = abs(diffY)
            val velocity = max(abs(velocityX), abs(velocityY))
            if ((absX >= SCREEN_SWIPE_DISTANCE_THRESHOLD || absY >= SCREEN_SWIPE_DISTANCE_THRESHOLD) &&
                velocity >= SCREEN_SWIPE_VELOCITY_THRESHOLD
            ) {
                onDirectionalSwipe?.invoke(if (absX >= absY) diffX >= 0f else diffY >= 0f)
                return true
            }
            return false
        }
        override fun onDown(e: MotionEvent): Boolean = true
    })

    private val templePadGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val start = e1 ?: return false
            val diffX = e2.x - start.x
            val diffY = e2.y - start.y
            val absX = abs(diffX)
            val absY = abs(diffY)
            val velocity = max(abs(velocityX), abs(velocityY))
            if ((absX >= TEMPLE_PAD_SWIPE_DISTANCE_THRESHOLD || absY >= TEMPLE_PAD_SWIPE_DISTANCE_THRESHOLD) &&
                velocity >= TEMPLE_PAD_SWIPE_VELOCITY_THRESHOLD
            ) {
                onDirectionalSwipe?.invoke(if (absX >= absY) diffX >= 0f else diffY >= 0f)
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isTouchscreen = (event.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        if (!isTouchscreen) {
            return false
        }
        if (SystemClock.elapsedRealtime() < suppressViewTouchUntilMs) {
            return true
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    fun handleTemplePadMotionEvent(event: MotionEvent): Boolean {
        val normalizedEvent = normalizeTemplePadEvent(event)
        return try {
            templePadGestureDetector.onTouchEvent(normalizedEvent)
        } finally {
            if (normalizedEvent !== event) {
                normalizedEvent.recycle()
            }
        }
    }

    private fun normalizeTemplePadEvent(event: MotionEvent): MotionEvent {
        val normalizedAction = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_HOVER_MOVE -> MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_HOVER_EXIT -> MotionEvent.ACTION_UP
            else -> event.actionMasked
        }
        if (normalizedAction == event.actionMasked) {
            return event
        }
        return MotionEvent.obtain(event).apply {
            action = normalizedAction
        }
    }

    fun suppressViewTouchGestures(durationMs: Long = VIEW_GESTURE_SUPPRESSION_MS) {
        suppressViewTouchUntilMs = SystemClock.elapsedRealtime() + durationMs
    }

    fun handleTransitSwipe(forward: Boolean): Boolean {
        if (!isTransitSurface()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastTransitSwipeAtMs < TRANSIT_SWIPE_COOLDOWN_MS) return true
        lastTransitSwipeAtMs = now

        val entries = buildTransitRecapEntries()
        if (entries.isEmpty()) return true
        val activeIndex = findTransitActiveIndex(entries)
        val activeEntry = entries[activeIndex]
        syncTransitInteraction(activeEntry)

        val detailLines = buildTransitDetailLines(activeEntry)
        val maxDetailOffset = (detailLines.size - 4).coerceAtLeast(0)
        val baseTimelineStart = (activeIndex - 1).coerceAtLeast(0)
        val visibleRows = if (transitDetailExpanded) 2 else 4
        val maxTimelineOffset = (entries.size - baseTimelineStart - visibleRows).coerceAtLeast(0)
        val canExpand = detailLines.size > 1 || buildTransitTimelineSecondary(activeEntry).length > 38

        if (forward) {
            when {
                !transitDetailExpanded && canExpand -> transitDetailExpanded = true
                transitDetailExpanded && transitDetailOffset < maxDetailOffset -> transitDetailOffset += 1
                transitTimelineOffset < maxTimelineOffset -> transitTimelineOffset += 1
                else -> Unit
            }
        } else {
            when {
                transitTimelineOffset > 0 -> transitTimelineOffset -= 1
                transitDetailExpanded && transitDetailOffset > 0 -> transitDetailOffset -= 1
                transitDetailExpanded -> transitDetailExpanded = false
                else -> Unit
            }
        }
        postInvalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val contentView = state.normalizedContentView()
        when {
            state.previewActive && state.hasTransitContent() -> drawTransitRecapLayout(canvas, w, h)
            state.previewActive -> drawRoutePreviewLayout(canvas, w, h)
            contentView == HudContentView.TRANSIT_RECAP && state.hasTransitContent() -> drawTransitRecapLayout(canvas, w, h)
            contentView == HudContentView.FULL_MAP -> drawFullMapLayout(canvas, w, h)
            else -> drawCompactHudLayout(canvas, w, h)
        }
        if (contentView != HudContentView.HUD || state.previewActive) {
            drawStatusBar(canvas, w, h)
            drawModeIndicator(canvas, w, h)
        }
        if (!state.previewActive && contentView == HudContentView.HUD) {
            drawTurnAlertOverlay(canvas, w, h)
        }
        state.closingMessage?.let { drawClosingMessage(canvas, w, h, it) }
    }

    private fun drawClosingMessage(canvas: Canvas, w: Float, h: Float, message: String) {
        val overlayPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        val x = w / 2f
        val y = h / 2f - (msgPaint.descent() + msgPaint.ascent()) / 2f
        canvas.drawText(message, x, y, msgPaint)
    }

    private fun drawTurnAlertOverlay(canvas: Canvas, w: Float, h: Float) {
        if (!state.showTurnAlert) return
        if (state.instruction.isBlank()) return
        if (state.maneuver.contains("arrive", true)) return
        val dist = state.distToNextStep
        if (dist < 1.0 || dist > 200.0) return

        // Draw semi-transparent overlay
        canvas.drawRect(0f, h * 0.15f, w, h * 0.85f, turnAlertBgPaint)

        val cx = w / 2f
        val cy = h / 2f

        // Large maneuver arrow
        val sym = maneuverToArrow(state.maneuver)
        canvas.drawText(sym, cx, cy - 20f, turnAlertArrowPaint)

        // Distance
        val distStr = formatDistance(dist)
        canvas.drawText(distStr, cx, cy + 25f, turnAlertDistPaint)

        // Instruction text (truncated)
        val instrTrunc = truncateText(state.instruction, turnAlertInstrPaint, w * 0.85f)
        canvas.drawText(instrTrunc, cx, cy + 58f, turnAlertInstrPaint)
    }

    // ── Full-screen: map top 72%, text bottom 28% ─────────────────────────

    private fun drawCompactHudLayout(canvas: Canvas, w: Float, h: Float) {
        drawLiveHudLayout(canvas, w, h, mapWidthFraction = 0.38f, mapHeightFraction = 0.32f)
    }

    private fun drawFullMapLayout(canvas: Canvas, w: Float, h: Float) {
        val pad = 8f
        val mapTop = 34f
        val mapBottom = (h - 178f).coerceAtLeast(mapTop + 280f)
        val mapH = mapBottom - mapTop
        drawLiveMap(canvas, pad, mapTop, w - pad * 2f, mapH, showLandmarkLabels = true)
        drawCornerFrame(canvas, pad, mapTop, w - pad, mapBottom, 18f)
        drawCompass(canvas, w - 38f, mapTop + 32f, 22f)

        val navTop = mapBottom + 25f
        val navLeft = pad
        val navRight = w - pad
        val distancePaint = Paint(textPaint).apply {
            textSize = 24f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val instructionPaint = Paint(textPaint).apply {
            textSize = 17f
            color = hudGreen
        }
        val metaPaint = Paint(smallTextPaint).apply {
            textSize = 12f
            color = hudDimGreen
        }

        if (state.instruction.isBlank()) {
            canvas.drawText("Waiting for navigation", navLeft, navTop, instructionPaint)
        } else {
            val dist = formatDistance(effectiveStepDistance()).ifBlank { "--" }
            drawManeuverIcon(canvas, state.maneuver, navLeft + 20f, navTop - 8f, 34f)
            canvas.drawText(truncateText(dist, distancePaint, 120f), navLeft + 46f, navTop, distancePaint)
            drawWrappedText(
                canvas = canvas,
                text = cleanInstruction(state.instruction),
                paint = instructionPaint,
                left = navLeft,
                baseline = navTop + 26f,
                maxWidth = navRight - navLeft,
                maxLines = 4,
                lineHeight = 20f
            )
        }

        val meta = buildString {
            append(routeModeLabel())
            val remaining = formatDistance(state.totalDistance)
            if (remaining.isNotBlank()) append("  ").append(remaining)
            val eta = formatDuration(state.totalDuration)
            if (eta.isNotBlank()) append("  ").append(eta)
        }
        canvas.drawText(truncateText(meta, metaPaint, navRight - navLeft), navLeft, h - STATUS_SAFE_BOTTOM - 5f, metaPaint)
    }

    private fun drawFullScreenLayout(canvas: Canvas, w: Float, h: Float) {
        drawFullMapLayout(canvas, w, h)
    }

    private fun drawLiveHudLayout(
        canvas: Canvas,
        w: Float,
        h: Float,
        mapWidthFraction: Float,
        mapHeightFraction: Float
    ) {
        val pad = 10f
        val mapW = (w * mapWidthFraction).coerceIn(164f, 196f)
        val mapH = (h * mapHeightFraction).coerceIn(184f, 236f)
        val mapLeft = w - mapW - pad
        val mapTop = h - mapH - 16f
        val textW = (mapLeft - pad * 2f).coerceAtLeast(210f)

        drawMiniMapFrame(canvas, mapLeft, mapTop, mapW, mapH)
        drawCompass(canvas, mapLeft + mapW - 28f, mapTop + 30f, 21f)

        val statsBaseline = h - 34f
        drawNavigationHero(canvas, pad, statsBaseline, textW)
    }

    private fun drawMiniMapFrame(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        drawLiveMap(canvas, left, top, w, h)
    }

    private fun drawNavigationHero(canvas: Canvas, left: Float, statsBaseline: Float, maxWidth: Float) {
        if (state.instruction.isBlank()) {
            val waitingPaint = Paint(textPaint).apply {
                textSize = 20f
                color = hudGreen
            }
            canvas.drawText("Waiting for navigation", left, statsBaseline, waitingPaint)
            return
        }

        val isArrived = state.maneuver.contains("arrive", true) && state.stepDistance <= 0.0
        if (isArrived) {
            val checkPaint = Paint(textPaint).apply {
                textSize = 38f
                color = hudBrightGreen
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val arrivedPaint = Paint(textPaint).apply {
                textSize = 23f
                color = hudBrightGreen
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            canvas.drawText("\u2713", left, statsBaseline - 4f, checkPaint)
            drawWrappedText(canvas, "You have arrived", arrivedPaint, left + 42f, statsBaseline - 6f, maxWidth - 44f, 2, 25f)
            return
        }

        val distancePaint = Paint(textPaint).apply {
            textSize = 27f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val unitPaint = Paint(smallTextPaint).apply {
            textSize = 14f
            color = hudGreen
        }
        val instructionPaint = Paint(textPaint).apply {
            textSize = 18f
            color = hudGreen
        }

        val instructionTop = 36f
        val instructionWidth = (width.toFloat() - left * 2f).coerceAtLeast(maxWidth)
        drawWrappedText(
            canvas = canvas,
            text = cleanInstruction(state.instruction),
            paint = instructionPaint,
            left = left,
            baseline = instructionTop,
            maxWidth = instructionWidth,
            maxLines = 4,
            lineHeight = 21f
        )

        if (state.showSpeed) {
            val speedValue = formatSpeedValue()
            val speedUnit = formatSpeedUnit()
            val speedPaint = Paint(distancePaint).apply { textSize = 28f }
            canvas.drawText(speedValue, left + 24f, statsBaseline, speedPaint)
            canvas.drawText(speedUnit, left + 24f + speedPaint.measureText(speedValue) + 2f, statsBaseline, unitPaint)
        }

        val navX = left + maxWidth * 0.54f
        val dist = formatDistance(effectiveStepDistance()).ifBlank { "--" }
        drawManeuverIcon(canvas, state.maneuver, navX + 14f, statsBaseline - 9f, 36f)
        canvas.drawText(
            truncateText(dist, distancePaint, maxWidth - (navX - left) - 42f),
            navX + 42f,
            statsBaseline,
            distancePaint
        )
    }

    private fun drawManeuverIcon(canvas: Canvas, maneuver: String, cx: Float, cy: Float, size: Float) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudBrightGreen
            style = Paint.Style.STROKE
            strokeWidth = (size / 7f).coerceAtLeast(4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudBrightGreen
            style = Paint.Style.FILL
        }
        val m = maneuver.lowercase(Locale.US)
        when {
            m.contains("arrive") -> {
                canvas.drawCircle(cx, cy, size * 0.25f, fillPaint)
                canvas.drawCircle(cx, cy, size * 0.38f, strokePaint)
            }
            m.contains("left") -> {
                val path = Path().apply {
                    moveTo(cx + size * 0.30f, cy + size * 0.30f)
                    lineTo(cx + size * 0.30f, cy - size * 0.05f)
                    quadTo(cx + size * 0.30f, cy - size * 0.28f, cx + size * 0.06f, cy - size * 0.28f)
                    lineTo(cx - size * 0.22f, cy - size * 0.28f)
                }
                canvas.drawPath(path, strokePaint)
                drawArrowHead(canvas, cx - size * 0.30f, cy - size * 0.28f, 180f, size * 0.27f, fillPaint)
            }
            m.contains("right") -> {
                val path = Path().apply {
                    moveTo(cx - size * 0.30f, cy + size * 0.30f)
                    lineTo(cx - size * 0.30f, cy - size * 0.05f)
                    quadTo(cx - size * 0.30f, cy - size * 0.28f, cx - size * 0.06f, cy - size * 0.28f)
                    lineTo(cx + size * 0.22f, cy - size * 0.28f)
                }
                canvas.drawPath(path, strokePaint)
                drawArrowHead(canvas, cx + size * 0.30f, cy - size * 0.28f, 0f, size * 0.27f, fillPaint)
            }
            m.contains("uturn") -> {
                val rect = RectF(cx - size * 0.30f, cy - size * 0.34f, cx + size * 0.30f, cy + size * 0.26f)
                canvas.drawArc(rect, -70f, -250f, false, strokePaint)
                canvas.drawLine(cx - size * 0.30f, cy - size * 0.02f, cx - size * 0.30f, cy + size * 0.32f, strokePaint)
                drawArrowHead(canvas, cx - size * 0.30f, cy + size * 0.38f, 90f, size * 0.25f, fillPaint)
            }
            else -> {
                canvas.drawLine(cx, cy + size * 0.32f, cx, cy - size * 0.18f, strokePaint)
                drawArrowHead(canvas, cx, cy - size * 0.34f, -90f, size * 0.30f, fillPaint)
            }
        }
    }

    private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, directionDeg: Float, size: Float, paint: Paint) {
        val angle = Math.toRadians(directionDeg.toDouble())
        val backAngleA = angle + Math.toRadians(150.0)
        val backAngleB = angle - Math.toRadians(150.0)
        val backSize = size
        val wing = size * 0.58f
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(
                (tipX + cos(backAngleA) * backSize).toFloat(),
                (tipY + sin(backAngleA) * backSize).toFloat()
            )
            lineTo(
                (tipX + cos(angle + Math.PI) * wing).toFloat(),
                (tipY + sin(angle + Math.PI) * wing).toFloat()
            )
            lineTo(
                (tipX + cos(backAngleB) * backSize).toFloat(),
                (tipY + sin(backAngleB) * backSize).toFloat()
            )
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawRoutePreviewLayout(canvas: Canvas, w: Float, h: Float) {
        val pad = 8f
        val mapTop = 36f
        val mapH = h * 0.43f
        val mapW = w - 2 * pad
        drawRadarReticle(canvas, pad, mapTop, mapW, mapH)
        drawOverviewMap(canvas, pad, mapTop, mapW, mapH)
        drawCornerFrame(canvas, pad, mapTop, w - pad, mapTop + mapH, 18f)

        val titlePaint = Paint(textPaint).apply {
            textSize = 16f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val metaPaint = Paint(textPaint).apply {
            textSize = 16f
            color = hudGreen
            textAlign = Paint.Align.CENTER
        }
        val stepPaint = Paint(smallTextPaint).apply {
            textSize = 13f
            color = hudDimGreen
        }

        canvas.drawText("ROUTE OVERVIEW", w / 2f, 24f, titlePaint)
        var y = mapTop + mapH + 28f
        val summary = "${formatDistance(state.totalDistance)} | ${formatDuration(state.totalDuration)}"
        canvas.drawText(summary, w / 2f, y, metaPaint)
        y += 24f
        canvas.drawText("Start from phone to launch", pad, y, stepPaint)
        y += 16f
        canvas.drawLine(pad, y, w - pad, y, separatorPaint)
        y += 16f

        val previewSteps = state.allSteps.take(3)
        if (previewSteps.isEmpty()) {
            canvas.drawText("Waiting for route steps...", pad, y, stepPaint)
            return
        }

        previewSteps.forEachIndexed { index, step ->
            if (y > h - 28f) return@forEachIndexed
            val dist = formatDistance(step.distance)
            val text = "${index + 1}. ${cleanInstruction(step.instruction)}"
            val rowBottom = drawWrappedText(canvas, text, stepPaint, pad, y, w - pad * 2 - 74f, 2, 16f)
            canvas.drawText(dist, w - pad - 56f, y, stepPaint)
            y = rowBottom + 8f
        }
    }

    private fun drawTransitRecapLayout(canvas: Canvas, w: Float, h: Float) {
        val pad = 10f
        val titlePaint = Paint(textPaint).apply {
            textSize = 18f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val metaPaint = Paint(textPaint).apply {
            textSize = 14f
            color = hudGreen
        }
        val sectionPaint = Paint(smallTextPaint).apply {
            textSize = 13f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val linePaint = Paint(textPaint).apply {
            textSize = 24f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val detailPaint = Paint(textPaint).apply {
            textSize = 15f
            color = hudGreen
        }
        val footPaint = Paint(smallTextPaint).apply {
            textSize = 12f
            color = hudDimGreen
        }
        val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudGreen
            style = Paint.Style.FILL
        }

        var y = 26f
        canvas.drawText(if (state.previewActive) "TRANSIT PREVIEW" else "TRANSIT PLAN", pad, y, titlePaint)
        val hintPaint = Paint(footPaint).apply { textAlign = Paint.Align.RIGHT }
        val viewHint = when {
            state.previewActive -> "START PHONE"
            transitDetailExpanded -> "DETAIL"
            transitTimelineOffset > 0 -> "LATER"
            else -> "COMPACT"
        }
        canvas.drawText(viewHint, w - pad, y, hintPaint)
        y += 22f

        val recapEntries = buildTransitRecapEntries()
        val transitCount = recapEntries.count { it.isTransit }
        val changes = (transitCount - 1).coerceAtLeast(0)
        val summary = buildString {
            append("${formatDuration(state.totalDuration)} | ${formatDistance(state.totalDistance)}")
            if (transitCount > 0) append(" | $transitCount line${if (transitCount > 1) "s" else ""}")
            if (changes > 0) append(" | $changes change${if (changes > 1) "s" else ""}")
        }
        canvas.drawText(truncateText(summary, metaPaint, w - pad * 2), pad, y, metaPaint)
        y += 12f
        canvas.drawLine(pad, y, w - pad, y, separatorPaint)
        y += 18f

        if (recapEntries.isEmpty()) {
            canvas.drawText("Waiting for transit details...", pad, y, footPaint)
            return
        }

        val activeEntry = recapEntries.firstOrNull { state.currentStepIndex in it.stepIndex..it.endStepIndex }
            ?: recapEntries.firstOrNull { it.stepIndex >= state.currentStepIndex }
            ?: recapEntries.last()
        val activeIndex = recapEntries.indexOfFirst { it.stepIndex == activeEntry.stepIndex }.coerceAtLeast(0)
        syncTransitInteraction(activeEntry)
        val activeBottom = drawTransitActiveLeg(
            canvas = canvas,
            entry = activeEntry,
            left = pad,
            top = y,
            right = w - pad,
            sectionPaint = sectionPaint,
            linePaint = linePaint,
            detailPaint = detailPaint,
            footPaint = footPaint
        )

        val contentBottom = h - STATUS_SAFE_BOTTOM - 14f
        val timelineTop = activeBottom + 38f
        if (timelineTop < contentBottom - 24f) {
            drawTransitTimeline(
                canvas = canvas,
                entries = recapEntries,
                activeIndex = activeIndex,
                left = pad,
                top = timelineTop,
                right = w - pad,
                bottom = contentBottom,
                sectionPaint = sectionPaint,
                detailPaint = detailPaint,
                footPaint = footPaint,
                nodePaint = nodePaint,
                timelineOffset = transitTimelineOffset
            )
        }
    }

    private fun drawTransitActiveLeg(
        canvas: Canvas,
        entry: TransitRecapEntry,
        left: Float,
        top: Float,
        right: Float,
        sectionPaint: Paint,
        linePaint: Paint,
        detailPaint: Paint,
        footPaint: Paint
    ): Float {
        val detailLines = buildTransitDetailLines(entry)
        val expanded = transitDetailExpanded && detailLines.isNotEmpty()
        val bottom = top + if (expanded) 174f else 122f
        drawCornerFrame(canvas, left, top, right, bottom, 16f)

        var y = top + 20f
        canvas.drawText(if (entry.isTransit) "ACTIVE LINE" else "ACTIVE WALK", left + 12f, y, sectionPaint)
        y += 30f

        val primary = if (entry.isTransit) entry.lineLabel else "Walk"
        canvas.drawText(truncateText(primary, linePaint, right - left - 24f), left + 12f, y, linePaint)
        y += 24f

        val direction = when {
            entry.isTransit && entry.headsign.isNotBlank() -> "Toward ${entry.headsign}"
            entry.isTransit -> buildTransitStopSummary(entry)
            else -> buildWalkSummary(entry)
        }
        if (expanded) {
            val rowHeight = 18f
            val maxRows = ((bottom - y - 26f) / rowHeight).toInt().coerceAtLeast(1)
            val firstDetail = transitDetailOffset.coerceIn(0, (detailLines.size - 1).coerceAtLeast(0))
            detailLines.drop(firstDetail).take(maxRows).forEachIndexed { offset, detail ->
                val number = firstDetail + offset + 1
                val row = "$number. $detail"
                canvas.drawText(truncateText(row, detailPaint, right - left - 24f), left + 12f, y, detailPaint)
                y += rowHeight
            }
        } else {
            y = drawWrappedText(canvas, direction, detailPaint, left + 12f, y, right - left - 24f, 2, 18f)

            val stopLine = if (entry.isTransit) buildTransitStopSummary(entry) else ""
            if (stopLine.isNotBlank() && stopLine != direction && y < bottom - 18f) {
                y = drawWrappedText(canvas, stopLine, footPaint, left + 12f, y + 2f, right - left - 24f, 1, 15f)
            }
        }
        if (y < bottom - 12f) {
            val page = if (expanded && detailLines.size > 1) {
                "  ${transitDetailOffset + 1}/${detailLines.size}"
            } else {
                ""
            }
            canvas.drawText(buildTransitFootnote(entry) + page, left + 12f, bottom - 12f, footPaint)
        }
        return bottom
    }

    private fun drawTransitTimeline(
        canvas: Canvas,
        entries: List<TransitRecapEntry>,
        activeIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        sectionPaint: Paint,
        detailPaint: Paint,
        footPaint: Paint,
        nodePaint: Paint,
        timelineOffset: Int
    ) {
        canvas.drawText("TIMELINE", left, top - 8f, sectionPaint)
        val axisX = left + 14f
        val rowHeight = 52f
        val rowTop = top + 18f
        val maxRows = ((bottom - rowTop) / rowHeight).toInt().coerceIn(1, 4)
        val baseStartIndex = (activeIndex - 1).coerceAtLeast(0)
        val maxStartIndex = (entries.size - maxRows).coerceAtLeast(0)
        val startIndex = (baseStartIndex + timelineOffset).coerceIn(0, maxStartIndex)
        val rows = entries.drop(startIndex).take(maxRows)
        if (rows.isEmpty()) return

        val firstY = rowTop
        val lastY = rowTop + (rows.size - 1) * rowHeight
        canvas.drawLine(axisX, firstY - 8f, axisX, lastY + 12f, separatorPaint)

        rows.forEachIndexed { offset, entry ->
            val index = startIndex + offset
            val rowY = rowTop + offset * rowHeight
            val isActive = index == activeIndex
            nodePaint.color = if (isActive) hudBrightGreen else hudDimGreen
            canvas.drawCircle(axisX, rowY, if (isActive) 5.5f else 4f, nodePaint)

            val labelLeft = axisX + 18f
            val tag = when {
                isActive -> "NOW"
                entry.isTransit -> "LINE"
                else -> "WALK"
            }
            canvas.drawText(tag, labelLeft, rowY - 5f, sectionPaint)

            val primary = buildTransitTimelinePrimary(entries, index)
            canvas.drawText(
                truncateText(primary, detailPaint, right - labelLeft - 64f),
                labelLeft + 46f,
                rowY - 5f,
                detailPaint
            )
            canvas.drawText(
                truncateText(formatDistance(entry.distanceMeters), footPaint, 58f),
                right - 56f,
                rowY - 5f,
                footPaint
            )

            val secondary = buildTransitTimelineSecondary(entry)
            drawWrappedText(canvas, secondary, footPaint, labelLeft, rowY + 15f, right - labelLeft, 1, 14f)
        }

        val hidden = entries.size - startIndex - rows.size
        if (hidden > 0) {
            val hintY = rowTop + rows.size * rowHeight + 2f
            if (hintY < bottom) {
                canvas.drawText("+$hidden more", axisX + 18f, hintY, footPaint)
            }
        }
    }

    // ── Mini bottom (phone toggle): map 25% at bottom, direction+distance at bottom, no notifications ─

    private fun isTransitSurface(): Boolean {
        return state.hasTransitContent() &&
            (state.previewActive || state.normalizedContentView() == HudContentView.TRANSIT_RECAP)
    }

    private fun resetTransitInteraction() {
        transitDetailExpanded = false
        transitDetailOffset = 0
        transitTimelineOffset = 0
        lastTransitEntryKey = ""
    }

    private fun findTransitActiveIndex(entries: List<TransitRecapEntry>): Int {
        val activeEntry = entries.firstOrNull { state.currentStepIndex in it.stepIndex..it.endStepIndex }
            ?: entries.firstOrNull { it.stepIndex >= state.currentStepIndex }
            ?: entries.last()
        return entries.indexOfFirst { it.stepIndex == activeEntry.stepIndex }.coerceAtLeast(0)
    }

    private fun syncTransitInteraction(entry: TransitRecapEntry) {
        val key = "${entry.stepIndex}:${entry.endStepIndex}:${entry.rawInstruction}"
        if (key != lastTransitEntryKey) {
            transitDetailExpanded = false
            transitDetailOffset = 0
            transitTimelineOffset = 0
            lastTransitEntryKey = key
            return
        }
        val maxDetailOffset = (buildTransitDetailLines(entry).size - 4).coerceAtLeast(0)
        transitDetailOffset = transitDetailOffset.coerceIn(0, maxDetailOffset)
        transitTimelineOffset = transitTimelineOffset.coerceAtLeast(0)
    }

    private fun buildTransitDetailLines(entry: TransitRecapEntry): List<String> {
        return if (entry.isTransit) {
            listOf(
                if (entry.headsign.isNotBlank()) "Toward ${entry.headsign}" else "",
                buildTransitStopSummary(entry)
            ).filter { it.isNotBlank() }.distinctAdjacent()
        } else {
            entry.detailInstructions
                .ifEmpty { listOf(entry.rawInstruction) }
                .map { compactWalkInstruction(it) }
                .filter { it.isNotBlank() }
                .distinctAdjacent()
        }
    }

    private fun drawMiniBottomLayout(canvas: Canvas, w: Float, h: Float) {
        val dirStripH = 32f
        val mapHeight = h * 0.25f - dirStripH
        val mapTop = h - h * 0.25f
        val pad = 6f
        drawLiveMap(canvas, pad, mapTop + pad, w - 2 * pad, mapHeight - pad)
        drawCornerFrame(canvas, pad, mapTop + pad, w - pad, mapTop + mapHeight, 14f)
        drawCompass(canvas, w - 32f, mapTop + 20f, 20f)
        val dirTop = h - dirStripH - 4f
        drawDirections(canvas, pad, dirTop, w - 2 * pad)
    }

    // ── Mini split: bottom 25% — map left, directions right ───────────────

    private fun drawMiniSplitLayout(canvas: Canvas, w: Float, h: Float) {
        val stripH = h * 0.25f
        val stripTop = h - stripH
        val pad = 6f
        val halfW = w / 2f

        // Left half: directions
        val textLeft = pad
        val textW = halfW - 2 * pad
        val centerY = stripTop + stripH / 2f

        if (state.instruction.isBlank()) {
            val p = Paint(textPaint).apply { textSize = 16f }
            canvas.drawText("Waiting for nav...", textLeft, centerY + 6f, p)
        } else {
            val isArrived = state.maneuver.contains("arrive", true) && state.stepDistance <= 0.0
            if (isArrived) {
                val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 22f
                }
                val arrivedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = hudBrightGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 16f
                }
                canvas.drawText("\u2713", textLeft, centerY - 4f, checkPaint)
                canvas.drawText("Arrived!", textLeft + 28f, centerY - 4f, arrivedPaint)
            } else {
                val sym = maneuverToArrow(state.maneuver)
                val dist = formatDistance(effectiveStepDistance())

                // Maneuver arrow + distance
                val arrowPaintL = Paint(textPaint).apply { textSize = 28f }
                val distPaintL = Paint(textPaint).apply { textSize = 20f }
                canvas.drawText(sym, textLeft, centerY - 6f, arrowPaintL)
                canvas.drawText(dist, textLeft + 36f, centerY - 6f, distPaintL)

                // Instruction text (wrapped to fit)
                val instrPaint = Paint(smallTextPaint).apply { textSize = 14f; color = hudGreen }
                drawWrappedText(canvas, cleanInstruction(state.instruction), instrPaint, textLeft, centerY + 16f, textW, 2, 16f)
            }
        }

        // Right half: map
        val mapLeft = halfW + pad
        val mapW = halfW - 2 * pad
        drawLiveMap(canvas, mapLeft, stripTop + pad, mapW, stripH - 2 * pad)
        drawCornerFrame(canvas, mapLeft, stripTop + pad, mapLeft + mapW, stripTop + stripH - pad, 12f)
        drawCompass(canvas, w - pad - 20f, stripTop + pad + 20f, 16f)
    }

    // ── Small-corner: text left 62%, map bottom-right 38% ─────────────────

    private fun drawSmallCornerLayout(canvas: Canvas, w: Float, h: Float) {
        drawLiveHudLayout(canvas, w, h, mapWidthFraction = 0.34f, mapHeightFraction = 0.30f)
    }

    // ── Live tile map with bearing rotation ───────────────────────────────

    private fun drawLiveMap(
        canvas: Canvas,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        showLandmarkLabels: Boolean = false
    ) {
        canvas.save()
        canvas.clipRect(left, top, left + w, top + h)

        val cx = left + w / 2
        val cy = top + h / 2

        if (state.latitude == 0.0 && state.longitude == 0.0) {
            val p = Paint(smallTextPaint).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("Waiting for GPS...", cx, cy, p)
            canvas.restore()
            return
        }

        val n = (1 shl MAP_ZOOM).toDouble()
        val fracX = (state.longitude + 180.0) / 360.0 * n
        val latRad = Math.toRadians(state.latitude)
        val fracY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
        val gpxX = fracX * TILE_SIZE
        val gpxY = fracY * TILE_SIZE

        // Rotate map so heading points up
        canvas.save()
        canvas.rotate(-state.bearing, cx, cy)

        drawTiles(canvas, cx, cy, gpxX, gpxY, w, h, n)
        drawLandmarksOnTiles(canvas, cx, cy, gpxX, gpxY, n)

        if (state.waypoints.size >= 2) {
            drawRouteOnTiles(canvas, cx, cy, gpxX, gpxY, n)
        }

        canvas.restore() // undo rotation

        if (showLandmarkLabels) {
            drawLandmarkLabels(canvas, left, top, w, h, cx, cy, gpxX, gpxY, n)
        }

        // Player arrow always points up (direction of travel)
        drawPlayerArrow(canvas, cx, cy)

        canvas.restore() // undo clip
    }

    private fun drawTiles(
        canvas: Canvas, cx: Float, cy: Float,
        gpxX: Double, gpxY: Double, viewW: Float, viewH: Float, n: Double
    ) {
        val diag = sqrt(viewW * viewW + viewH * viewH)
        val margin = (diag / 2 + TILE_SIZE).toInt()
        val tilesMargin = margin / TILE_SIZE + 1
        val centerTileX = floor(gpxX / TILE_SIZE).toInt()
        val centerTileY = floor(gpxY / TILE_SIZE).toInt()
        val maxTile = n.toInt()

        for (dy in -tilesMargin..tilesMargin) {
            for (dx in -tilesMargin..tilesMargin) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                if (ty < 0 || ty >= maxTile) continue
                val wrappedTx = ((tx % maxTile) + maxTile) % maxTile

                val screenX = (tx * TILE_SIZE - gpxX + cx).toFloat()
                val screenY = (ty * TILE_SIZE - gpxY + cy).toFloat()

                val bmp = tileManager?.getTile(MAP_ZOOM, wrappedTx, ty)
                if (bmp != null && !bmp.isRecycled) {
                    tileDestRect.set(
                        screenX.toInt(), screenY.toInt(),
                        (screenX + TILE_SIZE).toInt(), (screenY + TILE_SIZE).toInt()
                    )
                    canvas.drawBitmap(bmp, null, tileDestRect, tilePaint)
                }
            }
        }
    }

    private fun drawLandmarksOnTiles(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        gpxX: Double,
        gpxY: Double,
        n: Double
    ) {
        if (!state.showLandmarks || state.landmarks.isEmpty()) return
        state.landmarks.take(10).forEach { poi ->
            val poiFracX = (poi.longitude + 180.0) / 360.0 * n
            val poiLatRad = Math.toRadians(poi.latitude)
            val poiFracY = (1.0 - ln(tan(poiLatRad) + 1.0 / cos(poiLatRad)) / Math.PI) / 2.0 * n
            val sx = (poiFracX * TILE_SIZE - gpxX + cx).toFloat()
            val sy = (poiFracY * TILE_SIZE - gpxY + cy).toFloat()
            val r = when {
                poi.priority >= 80 -> 5.5f
                poi.priority >= 60 -> 4.5f
                else -> 3.5f
            }
            canvas.drawCircle(sx, sy, r, landmarkFillPaint)
            canvas.drawCircle(sx, sy, r + 1.5f, landmarkPaint)
        }
    }

    private fun drawLandmarkLabels(
        canvas: Canvas,
        left: Float,
        top: Float,
        viewW: Float,
        viewH: Float,
        cx: Float,
        cy: Float,
        gpxX: Double,
        gpxY: Double,
        n: Double
    ) {
        if (!state.showLandmarks || state.landmarks.isEmpty()) return
        val labelPaint = Paint(smallTextPaint).apply {
            textSize = 11f
            color = hudDimGreen
        }
        val maxRight = left + viewW - 8f
        val maxBottom = top + viewH - 8f
        state.landmarks.take(7).forEach { poi ->
            val point = projectLivePoint(poi.latitude, poi.longitude, cx, cy, gpxX, gpxY, n)
            val rotated = rotatePoint(point.x, point.y, cx, cy, -state.bearing)
            if (rotated.x !in left + 8f..maxRight || rotated.y !in top + 16f..maxBottom) return@forEach
            val label = truncateText(poi.name, labelPaint, 96f)
            val labelX = (rotated.x + 8f).coerceAtMost(maxRight - labelPaint.measureText(label))
            canvas.drawText(label, labelX, rotated.y - 5f, labelPaint)
        }
    }

    private fun projectLivePoint(
        latitude: Double,
        longitude: Double,
        cx: Float,
        cy: Float,
        gpxX: Double,
        gpxY: Double,
        n: Double
    ): ScreenPoint {
        val fracX = (longitude + 180.0) / 360.0 * n
        val latRad = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        val fracY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
        return ScreenPoint(
            x = (fracX * TILE_SIZE - gpxX + cx).toFloat(),
            y = (fracY * TILE_SIZE - gpxY + cy).toFloat()
        )
    }

    private fun rotatePoint(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): ScreenPoint {
        val radians = Math.toRadians(degrees.toDouble())
        val sinV = sin(radians)
        val cosV = cos(radians)
        val dx = x - cx
        val dy = y - cy
        return ScreenPoint(
            x = (cx + dx * cosV - dy * sinV).toFloat(),
            y = (cy + dx * sinV + dy * cosV).toFloat()
        )
    }

    private fun drawRouteOnTiles(
        canvas: Canvas, cx: Float, cy: Float,
        gpxX: Double, gpxY: Double, n: Double
    ) {
        val path = Path()
        var first = true
        for (wp in state.waypoints) {
            val wpFracX = (wp.longitude + 180.0) / 360.0 * n
            val wpLatRad = Math.toRadians(wp.latitude)
            val wpFracY = (1.0 - ln(tan(wpLatRad) + 1.0 / cos(wpLatRad)) / Math.PI) / 2.0 * n
            val sx = (wpFracX * TILE_SIZE - gpxX + cx).toFloat()
            val sy = (wpFracY * TILE_SIZE - gpxY + cy).toFloat()
            if (first) { path.moveTo(sx, sy); first = false } else { path.lineTo(sx, sy) }
        }
        canvas.drawPath(path, routeGlowPaint)
        canvas.drawPath(path, routePaint)
    }

    private fun drawOverviewMap(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        if (state.waypoints.size < 2) return
        val projection = computeOverviewProjection(left, top, w, h) ?: return
        drawOverviewTiles(canvas, projection)

        val path = Path()
        state.waypoints.forEachIndexed { index, wp ->
            val (x, y) = projectOverviewPoint(wp, projection)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, routeGlowPaint)
        canvas.drawPath(path, routePaint)

        val start = projectOverviewPoint(state.waypoints.first(), projection)
        val end = projectOverviewPoint(state.waypoints.last(), projection)
        canvas.drawCircle(start.first, start.second, 5f, dotPaint)
        canvas.drawCircle(end.first, end.second, 6f, arrowPaint)
        val labelPaint = Paint(smallTextPaint).apply {
            textSize = 12f
            color = hudGreen
        }
        canvas.drawText("START", start.first + 8f, start.second - 6f, labelPaint)
        canvas.drawText("END", end.first + 8f, end.second - 6f, labelPaint)
    }

    private fun drawRadarReticle(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val cx = left + w / 2f
        val cy = top + h / 2f
        val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudFaintGreen
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val radiusStep = min(w, h) / 6f
        for (i in 1..3) {
            canvas.drawCircle(cx, cy, radiusStep * i, reticlePaint)
        }
        canvas.drawLine(left + 16f, cy, left + w - 16f, cy, reticlePaint)
        canvas.drawLine(cx, top + 12f, cx, top + h - 12f, reticlePaint)
    }

    private fun computeOverviewProjection(left: Float, top: Float, w: Float, h: Float): OverviewProjection? {
        val usableW = (w - 28f).coerceAtLeast(120f)
        val usableH = (h - 28f).coerceAtLeast(80f)
        val points = state.waypoints
        if (points.size < 2) return null

        for (zoom in MAX_OVERVIEW_ZOOM downTo MIN_OVERVIEW_ZOOM) {
            val worldPoints = points.map { lonToWorldX(it.longitude, zoom) to latToWorldY(it.latitude, zoom) }
            val rawMinX = worldPoints.minOf { it.first }
            val rawMaxX = worldPoints.maxOf { it.first }
            val rawMinY = worldPoints.minOf { it.second }
            val rawMaxY = worldPoints.maxOf { it.second }
            val spanX = (rawMaxX - rawMinX).coerceAtLeast(8.0)
            val spanY = (rawMaxY - rawMinY).coerceAtLeast(8.0)
            val paddedMinX = rawMinX - max(spanX * 0.18, 96.0)
            val paddedMaxX = rawMaxX + max(spanX * 0.18, 96.0)
            val paddedMinY = rawMinY - max(spanY * 0.18, 96.0)
            val paddedMaxY = rawMaxY + max(spanY * 0.18, 96.0)
            val paddedSpanX = paddedMaxX - paddedMinX
            val paddedSpanY = paddedMaxY - paddedMinY
            val scale = min(usableW / paddedSpanX, usableH / paddedSpanY)

            val minTileX = floor(paddedMinX / TILE_SIZE).toInt()
            val maxTileX = floor(paddedMaxX / TILE_SIZE).toInt()
            val minTileY = floor(paddedMinY / TILE_SIZE).toInt()
            val maxTileY = floor(paddedMaxY / TILE_SIZE).toInt()
            val tileCount = (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1)

            if (tileCount <= 64 && scale in 0.45..2.5) {
                val contentW = (paddedSpanX * scale).toFloat()
                val contentH = (paddedSpanY * scale).toFloat()
                val offsetX = left + (w - contentW) / 2f
                val offsetY = top + (h - contentH) / 2f
                return OverviewProjection(
                    zoom = zoom,
                    minWorldX = paddedMinX,
                    minWorldY = paddedMinY,
                    maxWorldX = paddedMaxX,
                    maxWorldY = paddedMaxY,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY
                )
            }
        }

        val zoom = MIN_OVERVIEW_ZOOM
        val worldPoints = points.map { lonToWorldX(it.longitude, zoom) to latToWorldY(it.latitude, zoom) }
        val rawMinX = worldPoints.minOf { it.first }
        val rawMaxX = worldPoints.maxOf { it.first }
        val rawMinY = worldPoints.minOf { it.second }
        val rawMaxY = worldPoints.maxOf { it.second }
        val spanX = (rawMaxX - rawMinX).coerceAtLeast(8.0)
        val spanY = (rawMaxY - rawMinY).coerceAtLeast(8.0)
        val paddedMinX = rawMinX - max(spanX * 0.18, 96.0)
        val paddedMaxX = rawMaxX + max(spanX * 0.18, 96.0)
        val paddedMinY = rawMinY - max(spanY * 0.18, 96.0)
        val paddedMaxY = rawMaxY + max(spanY * 0.18, 96.0)
        val paddedSpanX = paddedMaxX - paddedMinX
        val paddedSpanY = paddedMaxY - paddedMinY
        val scale = min(usableW / paddedSpanX, usableH / paddedSpanY)
        val contentW = (paddedSpanX * scale).toFloat()
        val contentH = (paddedSpanY * scale).toFloat()
        return OverviewProjection(
            zoom = zoom,
            minWorldX = paddedMinX,
            minWorldY = paddedMinY,
            maxWorldX = paddedMaxX,
            maxWorldY = paddedMaxY,
            scale = scale,
            offsetX = left + (w - contentW) / 2f,
            offsetY = top + (h - contentH) / 2f
        )
    }

    private fun drawOverviewTiles(canvas: Canvas, projection: OverviewProjection) {
        val tileManager = tileManager ?: return
        val minTileX = floor(projection.minWorldX / TILE_SIZE).toInt()
        val maxTileX = floor(projection.maxWorldX / TILE_SIZE).toInt()
        val minTileY = floor(projection.minWorldY / TILE_SIZE).toInt()
        val maxTileY = floor(projection.maxWorldY / TILE_SIZE).toInt()
        val maxTile = 1 shl projection.zoom

        for (tileY in minTileY..maxTileY) {
            if (tileY !in 0 until maxTile) continue
            for (tileX in minTileX..maxTileX) {
                val wrappedX = ((tileX % maxTile) + maxTile) % maxTile
                val bmp = tileManager.getTile(projection.zoom, wrappedX, tileY) ?: continue
                if (bmp.isRecycled) continue

                val worldLeft = tileX * TILE_SIZE.toDouble()
                val worldTop = tileY * TILE_SIZE.toDouble()
                val screenLeft = projection.offsetX + ((worldLeft - projection.minWorldX) * projection.scale).toFloat()
                val screenTop = projection.offsetY + ((worldTop - projection.minWorldY) * projection.scale).toFloat()
                val screenRight = screenLeft + (TILE_SIZE * projection.scale).toFloat()
                val screenBottom = screenTop + (TILE_SIZE * projection.scale).toFloat()
                tileDestRect.set(
                    screenLeft.toInt(),
                    screenTop.toInt(),
                    screenRight.toInt(),
                    screenBottom.toInt()
                )
                canvas.drawBitmap(bmp, null, tileDestRect, tilePaint)
            }
        }
    }

    private fun projectOverviewPoint(wp: Waypoint, projection: OverviewProjection): Pair<Float, Float> {
        val worldX = lonToWorldX(wp.longitude, projection.zoom)
        val worldY = latToWorldY(wp.latitude, projection.zoom)
        val x = projection.offsetX + ((worldX - projection.minWorldX) * projection.scale).toFloat()
        val y = projection.offsetY + ((worldY - projection.minWorldY) * projection.scale).toFloat()
        return x to y
    }

    private fun lonToWorldX(longitude: Double, zoom: Int): Double {
        val n = (1 shl zoom).toDouble()
        return ((longitude + 180.0) / 360.0 * n) * TILE_SIZE
    }

    private fun latToWorldY(latitude: Double, zoom: Int): Double {
        val n = (1 shl zoom).toDouble()
        val latRad = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n) * TILE_SIZE
    }

    private fun drawPlayerArrow(canvas: Canvas, cx: Float, cy: Float) {
        val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
        }
        val arrowPath = Path().apply {
            moveTo(cx, cy - 22f)
            lineTo(cx - 13f, cy + 18f)
            lineTo(cx, cy + 10f)
            lineTo(cx + 13f, cy + 18f)
            close()
        }
        canvas.drawPath(arrowPath, cutoutPaint)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.drawPath(arrowPath, arrowOutlinePaint)
    }

    // ── Compass ───────────────────────────────────────────────────────────

    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, compassPaint)
        canvas.save()
        canvas.rotate(-state.bearing, cx, cy)
        val nPaint = Paint(textPaint).apply { textSize = 14f; textAlign = Paint.Align.CENTER; color = hudGreen }
        canvas.drawText("N", cx, cy - radius + 14f, nPaint)
        canvas.drawLine(cx, cy, cx, cy - radius + 4f, dotPaint)
        canvas.restore()
        val degPaint = Paint(smallTextPaint).apply { textSize = 11f; textAlign = Paint.Align.CENTER }
        canvas.drawText("${state.bearing.toInt()}°", cx, cy + radius + 14f, degPaint)
    }

    // ── Directions ────────────────────────────────────────────────────────

    private fun effectiveStepDistance(): Double {
        return if (state.distToNextStep >= 0) state.distToNextStep else state.stepDistance
    }

    private fun drawDirections(canvas: Canvas, left: Float, top: Float, maxWidth: Float) {
        if (state.instruction.isBlank()) {
            val p = Paint(textPaint).apply { textSize = 18f }
            canvas.drawText("Waiting for navigation...", left, top + 20f, p)
            return
        }

        val isArrived = state.maneuver.contains("arrive", true) && state.stepDistance <= 0.0

        if (isArrived) {
            val arrivedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = hudBrightGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 24f
                textAlign = Paint.Align.CENTER
            }
            val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            val cx = left + maxWidth / 2
            canvas.drawText("\u2713", cx, top + 24f, checkPaint)
            canvas.drawText("You have arrived!", cx, top + 52f, arrivedPaint)
            return
        }

        val sym = maneuverToArrow(state.maneuver)
        val dist = formatDistance(effectiveStepDistance())
        val instrPaint = Paint(textPaint).apply { textSize = 20f }
        val distPaint = Paint(textPaint).apply { textSize = 18f; textAlign = Paint.Align.RIGHT }
        val instrTrunc = truncateText("$sym ${state.instruction}", instrPaint, maxWidth - 80f)
        canvas.drawText(instrTrunc, left, top + 22f, instrPaint)
        canvas.drawText(dist, left + maxWidth, top + 22f, distPaint)
        canvas.drawLine(left, top + 34f, left + maxWidth, top + 34f, separatorPaint)
    }

    // ── Notifications / upcoming steps area ──────────────────────────────

    private fun drawInfoArea(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        if (!state.streamNotifications && state.showUpcomingSteps && state.allSteps.isNotEmpty()) {
            drawUpcomingSteps(canvas, left, top, maxWidth, maxHeight)
        } else if (state.streamNotifications) {
            drawNotifications(canvas, left, top, maxWidth, maxHeight)
        }
        // If streamNotifications is off and showUpcomingSteps is off, draw nothing
    }

    private fun drawNotifications(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        if (state.notifications.isEmpty()) return
        var y = top + 6f
        val lineHeight = 30f
        for (notif in state.notifications) {
            if (y + lineHeight > top + maxHeight) break
            val title = notif.title ?: "Notification"
            val body = notif.text ?: ""
            canvas.drawText(truncateText(title, notifTitlePaint, maxWidth - 10f), left + 2f, y + 14f, notifTitlePaint)
            if (body.isNotBlank()) {
                canvas.drawText(truncateText(body, notifBodyPaint, maxWidth - 10f), left + 2f, y + 28f, notifBodyPaint)
            }
            y += lineHeight
            canvas.drawLine(left, y, left + maxWidth, y, separatorPaint)
            y += 4f
        }
    }

    private fun drawUpcomingSteps(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        // Show steps after the current one (current step is already in the directions bar)
        val startIdx = state.currentStepIndex + 1
        if (startIdx >= state.allSteps.size) return

        val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 14f
        }
        val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 13f; textAlign = Paint.Align.RIGHT
        }

        var y = top + 6f
        val lineHeight = 24f
        for (i in startIdx until state.allSteps.size) {
            if (y + lineHeight > top + maxHeight) break
            val step = state.allSteps[i]
            val sym = maneuverToArrow(step.maneuver)
            val dist = formatDistance(step.distance)
            val instr = truncateText("$sym ${step.instruction}", stepPaint, maxWidth - 70f)
            canvas.drawText(instr, left + 2f, y + 14f, stepPaint)
            canvas.drawText(dist, left + maxWidth - 2f, y + 14f, distPaint)
            y += lineHeight
            canvas.drawLine(left, y, left + maxWidth, y, separatorPaint)
            y += 3f
        }
    }

    // ── Status bar (BT + WiFi) ────────────────────────────────────────────

    private fun drawStatusBar(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; textAlign = Paint.Align.LEFT
        }
        val y = h - 9f
        var x = 8f

        p.color = hudGreen
        val timeLabel = statusClockFormat.format(Date())
        canvas.drawText(timeLabel, x, y, p)
        x += p.measureText(timeLabel) + 12f

        p.color = hudDimGreen
        val connectionLabel = when {
            !state.btConnected && !state.wifiConnected -> "BT-- WiFi--"
            !state.btConnected -> "BT--"
            !state.wifiConnected -> "WiFi--"
            else -> ""
        }
        if (connectionLabel.isNotBlank()) {
            canvas.drawText(connectionLabel, x, y, p)
        }

        if (state.batteryLevel >= 0) {
            p.color = hudGreen
            p.textAlign = Paint.Align.RIGHT
            val batLabel = "${state.batteryLevel}%"
            canvas.drawText(batLabel, w - 8f, y, p)
        }
    }

    // ── Mode indicator ────────────────────────────────────────────────────

    private fun drawModeIndicator(canvas: Canvas, w: Float, h: Float) {
        val label = if (state.previewActive) {
            "PREVIEW"
        } else when (state.normalizedContentView()) {
            HudContentView.HUD -> "HUD"
            HudContentView.TRANSIT_RECAP -> "TRN"
            HudContentView.FULL_MAP -> "MAP"
        }
        val p = Paint(smallTextPaint).apply { textSize = 11f; textAlign = Paint.Align.RIGHT }
        canvas.drawText(label, w - 8f, h - 24f, p)
    }

    private fun routeModeLabel(): String {
        return when (state.routeMode.lowercase(Locale.US)) {
            "walk", "walking" -> "WALK"
            "transit", "public_transport" -> "TRANSIT"
            "bike", "bicycle", "cycling" -> "BIKE"
            else -> "DRIVE"
        }
    }

    private fun buildTransitTimelinePrimary(entries: List<TransitRecapEntry>, index: Int): String {
        val entry = entries[index]
        if (!entry.isTransit) return "Walk"
        val previousTransit = entries.take(index).lastOrNull { it.isTransit }
        return if (previousTransit != null) {
            "Change to ${entry.lineLabel}"
        } else {
            "Take ${entry.lineLabel}"
        }
    }

    private fun buildTransitTimelineSecondary(entry: TransitRecapEntry): String {
        return when {
            entry.isTransit && entry.headsign.isNotBlank() -> {
                val stops = entry.stopCount?.let { " | $it stops" }.orEmpty()
                "Toward ${entry.headsign}$stops"
            }
            entry.isTransit -> buildTransitStopSummary(entry)
            else -> buildWalkSummary(entry)
        }
    }

    private fun buildTransitRecapEntries(): List<TransitRecapEntry> {
        val entries = mutableListOf<TransitRecapEntry>()
        val pendingWalkSteps = mutableListOf<Pair<Int, StepInfo>>()

        fun flushWalkSteps() {
            if (pendingWalkSteps.isEmpty()) return
            entries += buildWalkRecapEntry(pendingWalkSteps.toList())
            pendingWalkSteps.clear()
        }

        state.allSteps.forEachIndexed { index, step ->
            val isTransit = step.maneuver.equals("transit", ignoreCase = true)
            if (step.instruction.isBlank()) return@forEachIndexed
            if (isTransit) {
                flushWalkSteps()
                val lineLabel = extractBetween(step.instruction, "Take ", listOf(" toward ", " from ", " to ", " ("))
                    .ifBlank { "Transit" }
                val headsign = extractBetween(step.instruction, " toward ", listOf(" from ", " to ", " ("))
                val departure = extractBetween(step.instruction, " from ", listOf(" to ", " ("))
                val arrival = extractBetween(step.instruction, " to ", listOf(" ("))
                val stopCount = Regex("\\((\\d+) stops?\\)").find(step.instruction)?.groupValues?.getOrNull(1)?.toIntOrNull()
                entries += TransitRecapEntry(
                    stepIndex = index,
                    endStepIndex = index,
                    isTransit = true,
                    lineLabel = lineLabel,
                    headsign = headsign,
                    departure = departure,
                    arrival = arrival,
                    stopCount = stopCount,
                    distanceMeters = step.distance,
                    rawInstruction = step.instruction,
                    detailInstructions = listOf(cleanInstruction(step.instruction))
                )
            } else {
                pendingWalkSteps += index to step
            }
        }
        flushWalkSteps()
        return entries
    }

    private fun buildWalkRecapEntry(steps: List<Pair<Int, StepInfo>>): TransitRecapEntry {
        val first = steps.first()
        val last = steps.last()
        val instructions = steps
            .map { cleanInstruction(it.second.instruction) }
            .filter { it.isNotBlank() }
            .distinctAdjacent()
        return TransitRecapEntry(
            stepIndex = first.first,
            endStepIndex = last.first,
            isTransit = false,
            lineLabel = "Walk",
            headsign = "",
            departure = "",
            arrival = extractWalkTarget(last.second.instruction),
            stopCount = null,
            distanceMeters = steps.sumOf { it.second.distance },
            rawInstruction = first.second.instruction,
            detailInstructions = instructions
        )
    }

    private fun buildTransitPrimaryLabel(entry: TransitRecapEntry): String {
        return if (entry.isTransit) {
            entry.lineLabel
        } else {
            "Walk"
        }
    }

    private fun buildTransitStopSummary(entry: TransitRecapEntry): String {
        return when {
            entry.departure.isNotBlank() && entry.arrival.isNotBlank() -> "${entry.departure}  ->  ${entry.arrival}"
            entry.arrival.isNotBlank() -> entry.arrival
            entry.departure.isNotBlank() -> entry.departure
            else -> truncateText(entry.rawInstruction, smallTextPaint, width - 40f)
        }
    }

    private fun buildTransitFootnote(entry: TransitRecapEntry): String {
        return if (entry.isTransit) {
            buildString {
                entry.stopCount?.let { append("$it stops") }
                if (entry.distanceMeters > 0.0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(formatDistance(entry.distanceMeters))
                }
            }.ifBlank { formatDistance(entry.distanceMeters) }
        } else {
            "Walk ${formatDistance(entry.distanceMeters)}"
        }
    }

    private fun buildWalkSummary(entry: TransitRecapEntry): String {
        val details = entry.detailInstructions
            .ifEmpty { listOf(cleanInstruction(entry.rawInstruction)) }
            .map { compactWalkInstruction(it) }
            .filter { it.isNotBlank() }
            .distinctAdjacent()
        return summarizeWalkDetails(details).ifBlank {
            entry.arrival.ifBlank { "Walk" }
        }
    }

    private fun compactWalkInstruction(instruction: String): String {
        val cleaned = cleanInstruction(instruction)
        val target = substringAfterLastToken(cleaned, " toward ")
            .ifBlank { substringAfterLastToken(cleaned, " vers ") }
        if (target.isNotBlank()) return target

        if (cleaned.startsWith("Prendre la direction", ignoreCase = true)) {
            val street = substringAfterLastToken(cleaned, " sur ")
            if (street.isNotBlank()) return street
            return cleaned.replace(Regex("^Prendre la direction\\s+", RegexOption.IGNORE_CASE), "Direction ")
        }

        return stripWalkPrefix(cleaned)
    }

    private fun summarizeWalkDetails(details: List<String>): String {
        return when {
            details.size <= 3 -> details.joinToString(" -> ")
            else -> "${details.first()} -> ${details.last()} +${details.size - 2}"
        }
    }

    private fun stripWalkPrefix(text: String): String {
        val prefixes = listOf(
            "Walk to ",
            "Walk ",
            "Continue on ",
            "Head onto ",
            "Head ",
            "Marchez vers ",
            "Marcher vers ",
            "Continuer tout droit sur ",
            "Continuez tout droit sur ",
            "Continuer sur ",
            "Continuez sur ",
            "Tourner a gauche sur ",
            "Tournez a gauche sur ",
            "Tourner à gauche sur ",
            "Tournez à gauche sur ",
            "Tourner a droite sur ",
            "Tournez a droite sur ",
            "Tourner à droite sur ",
            "Tournez à droite sur ",
            "Prendre a gauche sur ",
            "Prendre à gauche sur ",
            "Prenez a gauche sur ",
            "Prenez à gauche sur ",
            "Prendre a droite sur ",
            "Prendre à droite sur ",
            "Prenez a droite sur ",
            "Prenez à droite sur ",
            "Prendre a gauche ",
            "Prendre à gauche ",
            "Prenez a gauche ",
            "Prenez à gauche ",
            "Prendre a droite ",
            "Prendre à droite ",
            "Prenez a droite ",
            "Prenez à droite ",
            "Tourner a gauche ",
            "Tournez a gauche ",
            "Tourner à gauche ",
            "Tournez à gauche ",
            "Tourner a droite ",
            "Tournez a droite ",
            "Tourner à droite ",
            "Tournez à droite "
        )
        val trimmed = text.trim()
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        return if (prefix != null) trimmed.substring(prefix.length).trim() else trimmed
    }

    private fun substringAfterLastToken(text: String, token: String): String {
        val index = text.lowercase(Locale.US).lastIndexOf(token.lowercase(Locale.US))
        return if (index >= 0) text.substring(index + token.length).trim() else ""
    }

    private fun List<String>.distinctAdjacent(): List<String> {
        val out = mutableListOf<String>()
        forEach { item ->
            if (out.lastOrNull() != item) out += item
        }
        return out
    }

    private fun extractBetween(text: String, startToken: String, endTokens: List<String>): String {
        val startIndex = text.indexOf(startToken, ignoreCase = true)
        if (startIndex < 0) return ""
        val fromIndex = startIndex + startToken.length
        val endIndex = endTokens
            .map { token ->
                val idx = text.indexOf(token, fromIndex, ignoreCase = true)
                if (idx >= 0) idx else Int.MAX_VALUE
            }
            .minOrNull()
            ?.takeIf { it != Int.MAX_VALUE }
            ?: text.length
        return text.substring(fromIndex, endIndex).trim()
    }

    private fun extractWalkTarget(instruction: String): String {
        return compactWalkInstruction(instruction)
    }

    private fun formatDuration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun drawCornerFrame(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        corner: Float
    ) {
        canvas.drawLine(left, top, left + corner, top, mapBorderPaint)
        canvas.drawLine(left, top, left, top + corner, mapBorderPaint)
        canvas.drawLine(right, top, right - corner, top, mapBorderPaint)
        canvas.drawLine(right, top, right, top + corner, mapBorderPaint)
        canvas.drawLine(left, bottom, left + corner, bottom, mapBorderPaint)
        canvas.drawLine(left, bottom, left, bottom - corner, mapBorderPaint)
        canvas.drawLine(right, bottom, right - corner, bottom, mapBorderPaint)
        canvas.drawLine(right, bottom, right, bottom - corner, mapBorderPaint)
    }

    private fun cleanInstruction(instruction: String): String {
        return instruction
            .replace("Acceder a vers", "Vers", ignoreCase = true)
            .replace("Accéder à vers", "Vers", ignoreCase = true)
            .replace("Head onto", "Continue on", ignoreCase = true)
            .replace(Regex("\\s+(Votre destination|Your destination).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        left: Float,
        baseline: Float,
        maxWidth: Float,
        maxLines: Int,
        lineHeight: Float
    ): Float {
        var y = baseline
        wrapText(text, paint, maxWidth, maxLines).forEach { line ->
            canvas.drawText(line, left, y, paint)
            y += lineHeight
        }
        return y
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val clean = cleanInstruction(text)
        if (clean.isBlank() || maxWidth <= 0f || maxLines <= 0) return emptyList()
        if (paint.measureText(clean) <= maxWidth) return listOf(clean)

        val lines = mutableListOf<String>()
        val words = clean.split(" ")
        var current = ""
        var index = 0

        while (index < words.size && lines.size < maxLines) {
            val word = words[index]
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
                index++
            } else if (current.isBlank()) {
                lines += truncateText(word, paint, maxWidth)
                index++
            } else {
                lines += current
                current = ""
            }
        }

        if (current.isNotBlank() && lines.size < maxLines) {
            lines += current
        }
        if (index < words.size && lines.isNotEmpty()) {
            lines[lines.lastIndex] = appendEllipsis(lines.last(), paint, maxWidth)
        }
        return lines
    }

    private fun appendEllipsis(text: String, paint: Paint, maxW: Float): String {
        val marker = "..."
        if (paint.measureText(text + marker) <= maxW) return text + marker
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText(marker) > maxW) end--
        return text.substring(0, end).trimEnd() + marker
    }

    private fun formatSpeedValue(): String {
        val speedValue = if (state.useImperial) {
            (state.speed * 2.23694f).roundToInt()
        } else {
            (state.speed * 3.6f).roundToInt()
        }
        val overLimit = state.showSpeedLimit &&
            state.speedLimitKmh > 0 &&
            (state.speed * 3.6f).roundToInt() > state.speedLimitKmh
        return if (overLimit) "!$speedValue" else speedValue.toString()
    }

    private fun formatSpeedUnit(): String {
        return if (state.useImperial) "mph" else "km/h"
    }

    private fun truncateText(text: String, paint: Paint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (paint.measureText(text) <= maxW) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("...") > maxW) end--
        return text.substring(0, end) + "..."
    }

    private fun formatDistance(meters: Double): String {
        if (meters < 1) return ""
        return if (state.useImperial) {
            val feet = meters * 3.28084
            val miles = meters / 1609.344
            when {
                miles >= 0.1 -> String.format("%.1f mi", miles)
                else -> String.format("%.0f ft", feet)
            }
        } else {
            when {
                meters >= 1000 -> String.format("%.1f km", meters / 1000)
                else -> String.format("%.0f m", meters)
            }
        }
    }

    private fun maneuverToArrow(maneuver: String): String = when {
        maneuver.contains("left", true) -> "←"
        maneuver.contains("right", true) -> "→"
        maneuver.contains("uturn", true) -> "↩"
        maneuver.contains("straight", true) -> "↑"
        maneuver.contains("arrive", true) -> "●"
        maneuver.contains("depart", true) -> "▶"
        maneuver.contains("merge", true) -> "⤵"
        maneuver.contains("ramp", true) -> "↗"
        maneuver.contains("fork", true) -> "⑂"
        else -> "↑"
    }
}
