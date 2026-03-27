package com.rokid.hud.glasses

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.*

class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TILE_SIZE = 256
        private const val MAP_ZOOM = 16
        private const val MIN_OVERVIEW_ZOOM = 3
        private const val MAX_OVERVIEW_ZOOM = 15
        private const val SCREEN_SWIPE_DISTANCE_THRESHOLD = 60
        private const val SCREEN_SWIPE_VELOCITY_THRESHOLD = 60
        private const val TEMPLE_PAD_SWIPE_DISTANCE_THRESHOLD = 100
        private const val TEMPLE_PAD_SWIPE_VELOCITY_THRESHOLD = 100
        private const val VIEW_GESTURE_SUPPRESSION_MS = 800L
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
        val isTransit: Boolean,
        val lineLabel: String,
        val headsign: String,
        val departure: String,
        val arrival: String,
        val stopCount: Int?,
        val distanceMeters: Double,
        val rawInstruction: String
    )

    // Monochrome green palette — these glasses only display green
    private val hudBrightGreen = Color.parseColor("#00FF00")
    private val hudGreen = Color.parseColor("#00CC00")
    private val hudDimGreen = Color.parseColor("#008800")
    private val hudDarkGreen = Color.parseColor("#004400")
    private val hudFaintGreen = Color.parseColor("#003300")

    // Green-only color matrix: converts all color channels to green luminance
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,       // R output = 0
            0.30f, 0.59f, 0.11f, 0f, 0f, // G output = luminance
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

    var state: HudState = HudState()
        set(value) { field = value; postInvalidate() }

    var tileManager: TileManager? = null
    var onLayoutToggle: (() -> Unit)? = null
    var onContentViewToggle: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    private var suppressViewTouchUntilMs = 0L

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onLayoutToggle?.invoke()
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
            if (!state.hasTransitContent()) return false
            val start = e1 ?: return false
            val diffX = e2.x - start.x
            val diffY = e2.y - start.y
            val absX = abs(diffX)
            val absY = abs(diffY)
            val velocity = max(abs(velocityX), abs(velocityY))
            if ((absX >= SCREEN_SWIPE_DISTANCE_THRESHOLD || absY >= SCREEN_SWIPE_DISTANCE_THRESHOLD) &&
                velocity >= SCREEN_SWIPE_VELOCITY_THRESHOLD
            ) {
                onContentViewToggle?.invoke()
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
            if (!state.hasTransitContent()) return false
            val start = e1 ?: return false
            val diffX = e2.x - start.x
            val diffY = e2.y - start.y
            val absX = abs(diffX)
            val absY = abs(diffY)
            val velocity = max(abs(velocityX), abs(velocityY))
            if ((absX >= TEMPLE_PAD_SWIPE_DISTANCE_THRESHOLD || absY >= TEMPLE_PAD_SWIPE_DISTANCE_THRESHOLD) &&
                velocity >= TEMPLE_PAD_SWIPE_VELOCITY_THRESHOLD
            ) {
                onContentViewToggle?.invoke()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (state.contentView == HudContentView.TRANSIT_RECAP) {
            drawTransitRecapLayout(canvas, w, h)
        } else if (state.previewActive) {
            drawRoutePreviewLayout(canvas, w, h)
        } else {
            when (state.layoutMode) {
                MapLayoutMode.FULL_SCREEN -> drawFullScreenLayout(canvas, w, h)
                MapLayoutMode.SMALL_CORNER -> drawSmallCornerLayout(canvas, w, h)
                MapLayoutMode.MINI_BOTTOM -> drawMiniBottomLayout(canvas, w, h)
                MapLayoutMode.MINI_SPLIT -> drawMiniSplitLayout(canvas, w, h)
            }
        }
        drawStatusBar(canvas, w)
        drawModeIndicator(canvas, w)
        if (!state.previewActive) {
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

    private fun drawFullScreenLayout(canvas: Canvas, w: Float, h: Float) {
        val mapH = h * 0.72f
        val pad = 8f
        drawLiveMap(canvas, pad, pad, w - 2 * pad, mapH - 2 * pad)
        canvas.drawRect(pad, pad, w - pad, mapH - pad, mapBorderPaint)
        drawCompass(canvas, w - 50f, 50f, 36f)

        val textTop = mapH + 4f
        drawDirections(canvas, pad, textTop, w - 2 * pad)
        val dirH = 44f
        drawInfoArea(canvas, pad, textTop + dirH, w - 2 * pad, h - textTop - dirH - pad)
    }

    private fun drawRoutePreviewLayout(canvas: Canvas, w: Float, h: Float) {
        val pad = 8f
        val mapH = h * 0.55f
        drawOverviewMap(canvas, pad, pad + 12f, w - 2 * pad, mapH - 2 * pad)
        canvas.drawRect(pad, pad + 12f, w - pad, mapH - pad, mapBorderPaint)

        val titlePaint = Paint(textPaint).apply {
            textSize = 19f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val metaPaint = Paint(textPaint).apply {
            textSize = 16f
            color = hudGreen
        }
        val stepPaint = Paint(smallTextPaint).apply {
            textSize = 14f
            color = hudDimGreen
        }

        var y = mapH + 18f
        canvas.drawText("Route Preview", pad, y, titlePaint)
        y += 24f
        canvas.drawText("${formatDistance(state.totalDistance)}  ·  ${formatDuration(state.totalDuration)}", pad, y, metaPaint)
        y += 20f
        canvas.drawText("Tap start on phone to launch navigation", pad, y, stepPaint)
        y += 18f
        canvas.drawLine(pad, y, w - pad, y, separatorPaint)
        y += 16f

        val previewSteps = state.allSteps.take(4)
        if (previewSteps.isEmpty()) {
            canvas.drawText("Waiting for route steps...", pad, y, stepPaint)
            return
        }

        previewSteps.forEachIndexed { index, step ->
            if (y > h - 18f) return@forEachIndexed
            val dist = formatDistance(step.distance)
            val text = truncateText("${index + 1}. ${step.instruction}", stepPaint, w - pad * 2 - 70f)
            canvas.drawText(text, pad, y, stepPaint)
            canvas.drawText(dist, w - pad - 56f, y, stepPaint)
            y += 20f
        }
    }

    private fun drawTransitRecapLayout(canvas: Canvas, w: Float, h: Float) {
        run {
        val pad = 10f
        val titlePaint = Paint(textPaint).apply {
            textSize = 19f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val metaPaint = Paint(textPaint).apply {
            textSize = 15f
            color = hudGreen
        }
        val sectionPaint = Paint(smallTextPaint).apply {
            textSize = 14f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val linePaint = Paint(textPaint).apply {
            textSize = 20f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val detailPaint = Paint(textPaint).apply {
            textSize = 15f
            color = hudGreen
        }
        val footPaint = Paint(smallTextPaint).apply {
            textSize = 13f
            color = hudDimGreen
        }
        val hintPaint = Paint(smallTextPaint).apply {
            textSize = 12f
            color = hudDimGreen
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudGreen
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 0, 70, 0)
            style = Paint.Style.FILL
        }

        var y = 28f
        canvas.drawText(if (state.previewActive) "Transit Preview" else "Transit Plan", pad, y, titlePaint)
        y += 22f

        val recapEntries = buildTransitRecapEntries()
        val transitCount = recapEntries.count { it.isTransit }
        val changes = (transitCount - 1).coerceAtLeast(0)
        val summary = buildString {
            append("${formatDuration(state.totalDuration)}  ·  ${formatDistance(state.totalDistance)}")
            if (transitCount > 0) append("  ·  $transitCount line${if (transitCount > 1) "s" else ""}")
            if (changes > 0) append("  ·  $changes change${if (changes > 1) "s" else ""}")
        }
        canvas.drawText(summary, pad, y, metaPaint)
        y += 12f
        canvas.drawLine(pad, y, w - pad, y, separatorPaint)
        y += 18f

        if (recapEntries.isEmpty()) {
            canvas.drawText("Waiting for transit details...", pad, y, hintPaint)
            return
        }

        val activeEntry = recapEntries.firstOrNull { it.stepIndex >= state.currentStepIndex } ?: recapEntries.last()
        val cardRect = RectF(pad, y, w - pad, min(h - 88f, y + 116f))
        canvas.drawRoundRect(cardRect, 10f, 10f, cardFillPaint)
        canvas.drawRoundRect(cardRect, 10f, 10f, cardBorderPaint)

        var cardY = cardRect.top + 18f
        canvas.drawText(if (activeEntry.isTransit) "NOW" else "WALK", pad + 12f, cardY, sectionPaint)
        canvas.drawText(
            truncateText(buildTransitPrimaryLabel(activeEntry), linePaint, w - pad * 2 - 84f),
            pad + 56f,
            cardY + 4f,
            linePaint
        )
        cardY += 28f

        val secondaryLine = if (activeEntry.headsign.isNotBlank()) {
            "Toward ${activeEntry.headsign}"
        } else {
            buildTransitStopSummary(activeEntry)
        }
        canvas.drawText(
            truncateText(secondaryLine, detailPaint, w - pad * 2 - 24f),
            pad + 12f,
            cardY,
            detailPaint
        )
        cardY += 20f

        val stopLine = buildTransitStopSummary(activeEntry)
        if (stopLine != secondaryLine) {
            canvas.drawText(
                truncateText(stopLine, detailPaint, w - pad * 2 - 24f),
                pad + 12f,
                cardY,
                detailPaint
            )
            cardY += 20f
        }

        canvas.drawText(
            truncateText(buildTransitFootnote(activeEntry), footPaint, w - pad * 2 - 24f),
            pad + 12f,
            cardY,
            footPaint
        )

        y = cardRect.bottom + 18f
        val upcomingEntries = recapEntries.filter { it.stepIndex > activeEntry.stepIndex }.take(3)
        if (upcomingEntries.isNotEmpty()) {
            canvas.drawText("Next", pad, y, sectionPaint)
            y += 16f
            upcomingEntries.forEach { entry ->
                if (y > h - 34f) return@forEach
                val row = if (entry.isTransit) {
                    "${buildTransitPrimaryLabel(entry)}  ->  ${entry.arrival.ifBlank { "Arrive" }}"
                } else {
                    "Walk to ${entry.arrival.ifBlank { entry.departure.ifBlank { "next stop" } }}"
                }
                canvas.drawText(
                    truncateText(row, detailPaint, w - pad * 2 - 70f),
                    pad,
                    y,
                    detailPaint
                )
                canvas.drawText(
                    truncateText(formatDistance(entry.distanceMeters), footPaint, 64f),
                    w - pad - 58f,
                    y,
                    footPaint
                )
                y += 18f
                if (y < h - 28f) {
                    canvas.drawLine(pad, y, w - pad, y, separatorPaint)
                    y += 10f
                }
            }
        }

        canvas.drawText("Slide to return to the map view", pad, h - 14f, hintPaint)
        }
        return

        val pad = 10f
        val titlePaint = Paint(textPaint).apply {
            textSize = 20f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val metaPaint = Paint(textPaint).apply {
            textSize = 16f
            color = hudGreen
        }
        val walkPaint = Paint(smallTextPaint).apply {
            textSize = 13f
            color = hudDimGreen
        }
        val transitPaint = Paint(smallTextPaint).apply {
            textSize = 16f
            color = hudBrightGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val hintPaint = Paint(smallTextPaint).apply {
            textSize = 12f
            color = hudDimGreen
        }

        var y = 26f
        canvas.drawText(if (state.previewActive) "Transit Preview" else "Transit Recap", pad, y, titlePaint)
        y += 22f
        canvas.drawText("${formatDistance(state.totalDistance)}  ·  ${formatDuration(state.totalDuration)}", pad, y, metaPaint)
        y += 12f
        canvas.drawLine(pad, y, w - pad, y, separatorPaint)
        y += 18f

        val steps = state.allSteps
        if (steps.isEmpty()) {
            canvas.drawText("Waiting for transit details...", pad, y, hintPaint)
            return
        }

        val maxY = h - 24f
        steps.take(6).forEachIndexed { index, step ->
            if (y >= maxY) return@forEachIndexed
            val isTransit = step.maneuver.equals("transit", ignoreCase = true)
            val paint = if (isTransit) transitPaint else walkPaint
            val prefix = if (isTransit) "LINE" else "WALK"
            val maxWidth = w - pad * 2 - 58f
            val line = truncateText("${index + 1}. $prefix ${step.instruction}", paint, maxWidth)
            canvas.drawText(line, pad, y, paint)
            val dist = formatDistance(step.distance)
            canvas.drawText(dist, w - pad - 54f, y, paint)
            y += if (isTransit) 24f else 20f
            if (y >= maxY) return@forEachIndexed
            canvas.drawLine(pad, y, w - pad, y, separatorPaint)
            y += 12f
        }

        if (y < maxY) {
            canvas.drawText("Slide to return to the map view", pad, maxY, hintPaint)
        }
    }

    // ── Mini bottom (phone toggle): map 25% at bottom, direction+distance at bottom, no notifications ─

    private fun drawMiniBottomLayout(canvas: Canvas, w: Float, h: Float) {
        val dirStripH = 32f
        val mapHeight = h * 0.25f - dirStripH
        val mapTop = h - h * 0.25f
        val pad = 6f
        drawLiveMap(canvas, pad, mapTop + pad, w - 2 * pad, mapHeight - pad)
        canvas.drawRect(pad, mapTop + pad, w - pad, mapTop + mapHeight, mapBorderPaint)
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

        // Left half: map
        drawLiveMap(canvas, pad, stripTop + pad, halfW - 2 * pad, stripH - 2 * pad)
        canvas.drawRect(pad, stripTop + pad, halfW - pad, stripTop + stripH - pad, mapBorderPaint)
        drawCompass(canvas, halfW - pad - 20f, stripTop + pad + 20f, 16f)

        // Right half: directions
        val textLeft = halfW + pad
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
                val instrTrunc = truncateText(state.instruction, instrPaint, textW)
                canvas.drawText(instrTrunc, textLeft, centerY + 16f, instrPaint)
            }
        }
    }

    // ── Small-corner: text left 62%, map bottom-right 38% ─────────────────

    private fun drawSmallCornerLayout(canvas: Canvas, w: Float, h: Float) {
        val mapW = w * 0.38f
        val mapH = h * 0.42f
        val mapLeft = w - mapW - 6f
        val mapTop = h - mapH - 6f
        val pad = 8f

        drawLiveMap(canvas, mapLeft, mapTop, mapW, mapH)
        canvas.drawRect(mapLeft, mapTop, mapLeft + mapW, mapTop + mapH, mapBorderPaint)
        drawCompass(canvas, mapLeft + mapW - 30f, mapTop - 36f, 28f)

        val textW = w - mapW - 20f
        drawDirections(canvas, pad, pad, textW)
        val dirH = 44f
        drawInfoArea(canvas, pad, pad + dirH, textW, h - dirH - 2 * pad)
    }

    // ── Live tile map with bearing rotation ───────────────────────────────

    private fun drawLiveMap(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
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

        if (state.waypoints.size >= 2) {
            drawRouteOnTiles(canvas, cx, cy, gpxX, gpxY, n)
        }

        canvas.restore() // undo rotation

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
        val arrowPath = Path().apply {
            moveTo(cx, cy - 16f)
            lineTo(cx - 10f, cy + 12f)
            lineTo(cx, cy + 5f)
            lineTo(cx + 10f, cy + 12f)
            close()
        }
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

    private fun drawStatusBar(canvas: Canvas, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; textAlign = Paint.Align.LEFT
        }
        val y = 14f
        var x = 8f

        p.color = if (state.btConnected) hudGreen else hudDimGreen
        val btLabel = if (state.btConnected) "BT:ON" else "BT:--"
        canvas.drawText(btLabel, x, y, p)
        x += p.measureText(btLabel) + 10f

        p.color = if (state.wifiConnected) hudGreen else hudDimGreen
        val wifiLabel = if (state.wifiConnected) "WiFi:ON" else "WiFi:--"
        canvas.drawText(wifiLabel, x, y, p)
        x += p.measureText(wifiLabel) + 10f

        if (state.batteryLevel >= 0) {
            p.color = hudGreen
            val batLabel = "BAT:${state.batteryLevel}%"
            canvas.drawText(batLabel, x, y, p)
            x += p.measureText(batLabel) + 10f
        }

        // Speed display (always show when enabled)
        if (state.showSpeed) {
            val speedVal: Int
            val speedUnit: String
            if (state.useImperial) {
                speedVal = (state.speed * 2.23694f).toInt() // m/s to mph
                speedUnit = "mph"
            } else {
                speedVal = (state.speed * 3.6f).toInt() // m/s to km/h
                speedUnit = "km/h"
            }

            // Check if over speed limit (only highlight red when limit display is also on)
            val overLimit = if (state.showSpeedLimit && state.speedLimitKmh > 0) {
                val currentKmh = (state.speed * 3.6f).toInt()
                currentKmh > state.speedLimitKmh
            } else false

            p.color = hudGreen
            val speedPrefix = if (overLimit) "! " else ""
            val speedLabel = "$speedPrefix$speedVal $speedUnit"
            canvas.drawText(speedLabel, x, y, p)
            x += p.measureText(speedLabel) + 10f

            // Speed limit if available and enabled
            if (state.showSpeedLimit && state.speedLimitKmh > 0) {
                val limitVal = if (state.useImperial) {
                    (state.speedLimitKmh / 1.60934).toInt()
                } else state.speedLimitKmh
                val limitUnit = if (state.useImperial) "mph" else "km/h"
                p.color = hudDimGreen
                val limitLabel = "lim:$limitVal$limitUnit"
                canvas.drawText(limitLabel, x, y, p)
            }
        }
    }

    // ── Mode indicator ────────────────────────────────────────────────────

    private fun drawModeIndicator(canvas: Canvas, w: Float) {
        val label = if (state.contentView == HudContentView.TRANSIT_RECAP) {
            "[ TRANSIT ]"
        } else if (state.previewActive) {
            "[ PREVIEW ]"
        } else when (state.layoutMode) {
            MapLayoutMode.FULL_SCREEN -> "[ FULL ]"
            MapLayoutMode.SMALL_CORNER -> "[ CORNER ]"
            MapLayoutMode.MINI_BOTTOM -> "[ STRIP ]"
            MapLayoutMode.MINI_SPLIT -> "[ SPLIT ]"
        }
        val p = Paint(smallTextPaint).apply { textSize = 11f; textAlign = Paint.Align.RIGHT }
        canvas.drawText(label, w - 8f, 14f, p)
    }

    private fun buildTransitRecapEntries(): List<TransitRecapEntry> {
        return state.allSteps.mapIndexedNotNull { index, step ->
            val isTransit = step.maneuver.equals("transit", ignoreCase = true)
            if (step.instruction.isBlank()) return@mapIndexedNotNull null
            if (isTransit) {
                val lineLabel = extractBetween(step.instruction, "Take ", listOf(" toward ", " from ", " to ", " ("))
                    .ifBlank { "Transit" }
                val headsign = extractBetween(step.instruction, " toward ", listOf(" from ", " to ", " ("))
                val departure = extractBetween(step.instruction, " from ", listOf(" to ", " ("))
                val arrival = extractBetween(step.instruction, " to ", listOf(" ("))
                val stopCount = Regex("\\((\\d+) stops?\\)").find(step.instruction)?.groupValues?.getOrNull(1)?.toIntOrNull()
                TransitRecapEntry(
                    stepIndex = index,
                    isTransit = true,
                    lineLabel = lineLabel,
                    headsign = headsign,
                    departure = departure,
                    arrival = arrival,
                    stopCount = stopCount,
                    distanceMeters = step.distance,
                    rawInstruction = step.instruction
                )
            } else {
                TransitRecapEntry(
                    stepIndex = index,
                    isTransit = false,
                    lineLabel = "Walk",
                    headsign = "",
                    departure = "",
                    arrival = extractWalkTarget(step.instruction),
                    stopCount = null,
                    distanceMeters = step.distance,
                    rawInstruction = step.instruction
                )
            }
        }
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
        return extractBetween(instruction, "to ", listOf(" then ", " and "))
            .ifBlank { extractBetween(instruction, "toward ", listOf(" then ", " and ")) }
            .ifBlank { instruction.removePrefix("Walk").trim() }
    }

    private fun formatDuration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun truncateText(text: String, paint: Paint, maxW: Float): String {
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
