package com.blvckson.aviatorbehaviour

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class UltraScanEngine {
    private var previous: Bitmap? = null
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastV = 0f
    private var lastT = 0L
    private var similarity = 0.0
    private var similarityHold = 0

    fun inspect(frame: Bitmap, now: Long): List<VisualEvent> {
        val out = ArrayList<VisualEvent>()
        val p = previous
        if (p != null && p.width == frame.width && p.height == frame.height) {
            val change = sampleChange(p, frame)
            if (change > 0.018) out.add(VisualEvent(now, "FRAME_CHANGE", change, "whole-screen visual change"))

            val centerChange = roiChange(p, frame, 0.18, 0.18, 0.82, 0.62)
            if (centerChange > 0.012)
                out.add(VisualEvent(now, "MULTIPLIER_VISUAL_CHANGE", centerChange, "central multiplier-area visual change"))

            val redDensity = redDensity(frame, 0.15, 0.10, 0.90, 0.72)
            if (redDensity > 0.018)
                out.add(VisualEvent(now, "ENDING_RED_VISUAL", redDensity * 100.0, "red ending-state visual detected"))

            val stable = centerChange < 0.010
            if (stable && redDensity > 0.018)
                out.add(VisualEvent(now, "MULTIPLIER_VISUAL_STABLE", 1.0, "central multiplier-area visually stable while red"))

            val plane = trackPlane(frame, now)
            val speed: Double = if (plane.found) sqrt((plane.vx * plane.vx + plane.vy * plane.vy).toDouble()) else 0.0
            val accel = abs(plane.acceleration.toDouble())
            if (plane.found && (speed > 5.0 || accel > 18.0))
                out.add(VisualEvent(now, "PLANE_MOTION", speed, "x="+plane.x+",y="+plane.y+",acc="+plane.acceleration))
            else if (!plane.found && !lastX.isNaN())
                out.add(VisualEvent(now, "PLANE_DISAPPEAR", 1.0, "possible round transition"))

            val motionScore = min(1.0, speed / 55.0)
            val accelScore = min(1.0, accel / 120.0)
            val transitionScore = min(1.0, change / 0.22)
            val centerScore = min(1.0, centerChange / 0.12)
            val candidate = motionScore * 0.28 + accelScore * 0.20 + transitionScore * 0.27 + centerScore * 0.25
            similarity = similarity * 0.72 + candidate * 0.28
            if (similarity > 0.52) similarityHold++ else similarityHold = max(0, similarityHold - 1)

            if (similarity > 0.45) {
                val pct = (similarity * 100.0).coerceIn(0.0, 99.0)
                val strength = when {
                    similarity >= 0.82 -> "HIGH"
                    similarity >= 0.65 -> "STRONG"
                    else -> "MATCH"
                }
                out.add(VisualEvent(now, "BEHAVIOUR_SIMILARITY", pct,
                    "$strength; plane + multiplier-area + screen transition"))
            }
            if (similarityHold >= 3 && similarity >= 0.72)
                out.add(VisualEvent(now, "PRE_FLY_AWAY_MATCH", similarity * 100.0,
                    "behaviour similarity strengthening before transition"))

            if (change > 0.16 || (!plane.found && !lastX.isNaN()) || (redDensity > 0.06 && stable))
                out.add(VisualEvent(now, "ULTRAWATCH", max(change, similarity),
                    "sudden transition / possible fly-away"))

            if (redDensity > 0.06 && stable)
                out.add(VisualEvent(now, "ENDING_STATE_CLUSTER", redDensity * 100.0,
                    "red + visually stable multiplier area; possible FLEW AWAY state"))
        }
        previous = frame.copy(Bitmap.Config.ARGB_8888, true)
        return out
    }

    fun currentSimilarity(): Double = similarity
    fun lastPlaneSeenAt(): Long = lastT

    private fun sampleChange(a: Bitmap, b: Bitmap): Double {
        val sx = 10; val sy = 10
        var total = 0L; var n = 0
        for (j in 0 until sy) for (i in 0 until sx) {
            val x = i * (b.width - 1) / (sx - 1)
            val y = j * (b.height - 1) / (sy - 1)
            val ca = a.getPixel(x, y); val cb = b.getPixel(x, y)
            total += abs(((ca shr 16) and 255) - ((cb shr 16) and 255)).toLong()
            total += abs(((ca shr 8) and 255) - ((cb shr 8) and 255)).toLong()
            total += abs((ca and 255) - (cb and 255)).toLong(); n += 3
        }
        return total.toDouble() / (n * 255.0)
    }

    private fun roiChange(a: Bitmap, b: Bitmap, l: Double, t: Double, r: Double, bot: Double): Double {
        val x0 = (b.width * l).toInt().coerceIn(0, b.width - 1)
        val x1 = (b.width * r).toInt().coerceIn(x0 + 1, b.width)
        val y0 = (b.height * t).toInt().coerceIn(0, b.height - 1)
        val y1 = (b.height * bot).toInt().coerceIn(y0 + 1, b.height)
        val sx = 8; val sy = 5
        var total = 0L; var n = 0
        for (j in 0 until sy) for (i in 0 until sx) {
            val x = x0 + i * (x1 - x0 - 1) / (sx - 1)
            val y = y0 + j * (y1 - y0 - 1) / (sy - 1)
            val ca = a.getPixel(x, y); val cb = b.getPixel(x, y)
            total += abs(((ca shr 16) and 255) - ((cb shr 16) and 255)).toLong()
            total += abs(((ca shr 8) and 255) - ((cb shr 8) and 255)).toLong()
            total += abs((ca and 255) - (cb and 255)).toLong(); n += 3
        }
        return total.toDouble() / (n * 255.0)
    }

    private fun redDensity(b: Bitmap, l: Double, t: Double, r: Double, bot: Double): Double {
        val x0 = (b.width * l).toInt().coerceIn(0, b.width - 1)
        val x1 = (b.width * r).toInt().coerceIn(x0 + 1, b.width)
        val y0 = (b.height * t).toInt().coerceIn(0, b.height - 1)
        val y1 = (b.height * bot).toInt().coerceIn(y0 + 1, b.height)
        val sx = 10; val sy = 7
        var red = 0
        var n = 0
        for (j in 0 until sy) for (i in 0 until sx) {
            val x = x0 + i * (x1 - x0 - 1) / (sx - 1)
            val y = y0 + j * (y1 - y0 - 1) / (sy - 1)
            val c = b.getPixel(x, y)
            val rr = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
            if (rr > 150 && rr > g * 1.35 && rr > bl * 1.35) red++
            n++
        }
        return red.toDouble() / n
    }

    private fun trackPlane(b: Bitmap, t: Long): PlaneState {
        var sx = 0.0; var sy = 0.0; var n = 0; val step = 6
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b.getPixel(x, y)
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
            if (r > 170 && r > g * 1.25 && r > bl * 1.25) { sx += x; sy += y; n++ }
        }
        val found = n > 4
        val x = if (found) (sx / n).toFloat() else Float.NaN
        val y = if (found) (sy / n).toFloat() else Float.NaN
        var vx = 0f; var vy = 0f; var acc = 0f
        if (found && !lastX.isNaN() && lastT > 0) {
            val dt = ((t - lastT).coerceAtLeast(1)) / 1000f
            vx = (x - lastX) / dt; vy = (y - lastY) / dt
            val s = sqrt(vx * vx + vy * vy); acc = (s - lastV) / dt; lastV = s
        }
        if (found) { lastX = x; lastY = y; lastT = t }
        return PlaneState(found, x, y, vx, vy, acc)
    }
}