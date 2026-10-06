package com.blvckson.aviatorbehaviour

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class UltraScanEngine {
    private var previous: Bitmap? = null
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastV = 0f
    private var lastT = 0L
    private var lastDx = 0f
    private var lastDy = 0f
    private var movementConsistency = 0.0
    private var similarity = 0.0
    private var similarityHold = 0
    private var scanFrame = 0
    private var lastPlaneScanFrame = -1
    private var lostPlaneFrames = 0
    private var lastChange = 0.0
    private var changeVelocity = 0.0
    private var transitionEvidence = 0.0

    fun inspect(frame: Bitmap, now: Long): List<VisualEvent> {
        val out = ArrayList<VisualEvent>(8)
        scanFrame++
        val p = previous
        if (p != null && p.width == frame.width && p.height == frame.height) {
            val change = sampleChangeFocused(p, frame)
            changeVelocity = changeVelocity * 0.65 + (change - lastChange) * 0.35
            lastChange = change
            if (change > 0.018) out.add(VisualEvent(now, "FRAME_CHANGE", change, "whole-screen appearance/graphics change"))

            val graphics = graphicsChangeFocused(p, frame)
            if (graphics > 0.012) out.add(VisualEvent(now, "GRAPHICS_APPEARANCE", graphics * 100.0, "whole-screen graphics/appearance signature change"))

            val centerChange = roiChange(p, frame, focusLeft, focusTop, focusRight, focusBottom)
            if (centerChange > 0.012)
                out.add(VisualEvent(now, "MULTIPLIER_VISUAL_CHANGE", centerChange, "central multiplier-area visual change"))

            val redDensity = redDensity(frame, focusLeft, focusTop, focusRight, focusBottom)
            if (redDensity > 0.018)
                out.add(VisualEvent(now, "ENDING_RED_VISUAL", redDensity * 100.0, "red ending-state visual detected"))

            val stable = centerChange < 0.010
            if (stable && redDensity > 0.018)
                out.add(VisualEvent(now, "MULTIPLIER_VISUAL_STABLE", 1.0, "central multiplier-area visually stable while red"))

            val plane = trackPlane(frame, now, scanFrame)
            val speed: Double = if (plane.found) sqrt((plane.vx * plane.vx + plane.vy * plane.vy).toDouble()) else 0.0
            val accel = abs(plane.acceleration.toDouble())
            if (plane.found && (speed > 5.0 || accel > 18.0))
                out.add(VisualEvent(now, "PLANE_MOTION", speed, "x="+plane.x+",y="+plane.y+",acc="+plane.acceleration))
            else if (!plane.found && !lastX.isNaN())
                out.add(VisualEvent(now, "PLANE_DISAPPEAR", 1.0, "possible round transition"))

            val movementScore = movementConsistency
            val transitionVelocity = min(1.0, abs(changeVelocity) / 0.035)
            transitionEvidence = transitionEvidence * 0.78 + (transitionVelocity * 0.55 + min(1.0, change / 0.14) * 0.45) * 0.22
            val appearanceScore = min(1.0, (change * 0.55 + graphics * 0.45) / 0.16)
            val transitionScore = min(1.0, change / 0.22)
            val multiplierVisualScore = min(1.0, centerChange / 0.12)
            // Similarity uses appearance/graphics, movement behaviour and multiplier-area
            // visual behaviour. Raw multiplier figures, speed and acceleration are excluded.
            val candidate = movementScore * 0.30 + appearanceScore * 0.22 +
                transitionScore * 0.20 + multiplierVisualScore * 0.18 + transitionEvidence * 0.10
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

            if (change > 0.12 || transitionEvidence > 0.58 || (!plane.found && lostPlaneFrames >= 1) || (redDensity > 0.045 && stable))
                out.add(VisualEvent(now, "ULTRAWATCH", max(change, similarity),
                    "sudden transition / possible fly-away"))

            if (redDensity > 0.045 && stable)
                out.add(VisualEvent(now, "ENDING_STATE_CLUSTER", redDensity * 100.0,
                    "red + visually stable multiplier area; possible FLEW AWAY state"))
        }
        previous = frame.copy(Bitmap.Config.ARGB_8888, true)
        return out
    }

    fun currentSimilarity(): Double = similarity
    fun lastPlaneSeenAt(): Long = lastT

    private val focusLeft = 0.08
    private val focusTop = 0.08
    private val focusRight = 0.92
    private val focusBottom = 0.72

    private fun graphicsChangeFocused(a: Bitmap, b: Bitmap): Double {
        val x0=(b.width*focusLeft).toInt(); val x1=(b.width*focusRight).toInt()
        val y0=(b.height*focusTop).toInt(); val y1=(b.height*focusBottom).toInt()
        val sx=14; val sy=11
        var total=0.0; var n=0
        for(j in 0 until sy) for(i in 0 until sx){
            val x=x0+i*(x1-x0-1)/max(1,sx-1); val y=y0+j*(y1-y0-1)/max(1,sy-1)
            val ca=a.getPixel(x,y); val cb=b.getPixel(x,y)
            val ar=(ca shr 16) and 255; val ag=(ca shr 8) and 255; val ab=ca and 255
            val br=(cb shr 16) and 255; val bg=(cb shr 8) and 255; val bb=cb and 255
            val la=(0.299*ar+0.587*ag+0.114*ab)/255.0; val lb=(0.299*br+0.587*bg+0.114*bb)/255.0
            val caa=((ar-ag).absoluteValue+(ag-ab).absoluteValue)/510.0; val cbb=((br-bg).absoluteValue+(bg-bb).absoluteValue)/510.0
            total+=abs(la-lb)*0.62+abs(caa-cbb)*0.38; n++
        }
        return total/max(1,n)
    }

    private fun graphicsChange(a: Bitmap, b: Bitmap): Double {
        val sx = 12; val sy = 12
        var total = 0.0; var n = 0
        for (j in 0 until sy) for (i in 0 until sx) {
            val x = i * (b.width - 1) / (sx - 1)
            val y = j * (b.height - 1) / (sy - 1)
            val ca = a.getPixel(x, y); val cb = b.getPixel(x, y)
            val ar = (ca shr 16) and 255; val ag = (ca shr 8) and 255; val ab = ca and 255
            val br = (cb shr 16) and 255; val bg = (cb shr 8) and 255; val bb = cb and 255
            val la = (0.299 * ar + 0.587 * ag + 0.114 * ab) / 255.0
            val lb = (0.299 * br + 0.587 * bg + 0.114 * bb) / 255.0
            val caa = ((ar - ag).absoluteValue + (ag - ab).absoluteValue) / 510.0
            val cbb = ((br - bg).absoluteValue + (bg - bb).absoluteValue) / 510.0
            total += abs(la - lb) * 0.65 + abs(caa - cbb) * 0.35
            n++
        }
        return total / max(1, n)
    }

    private fun sampleChangeFocused(a: Bitmap, b: Bitmap): Double {
        val x0=(b.width*focusLeft).toInt(); val x1=(b.width*focusRight).toInt()
        val y0=(b.height*focusTop).toInt(); val y1=(b.height*focusBottom).toInt()
        val sx=13; val sy=9
        var total=0L; var n=0
        for(j in 0 until sy) for(i in 0 until sx){
            val x=x0+i*(x1-x0-1)/max(1,sx-1); val y=y0+j*(y1-y0-1)/max(1,sy-1)
            val ca=a.getPixel(x,y); val cb=b.getPixel(x,y)
            total+=abs(((ca shr 16) and 255)-((cb shr 16) and 255)).toLong()
            total+=abs(((ca shr 8) and 255)-((cb shr 8) and 255)).toLong()
            total+=abs((ca and 255)-(cb and 255)).toLong(); n+=3
        }
        return total.toDouble()/max(1,n*255)
    }

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
            if (rr.toDouble() > 150.0 && rr.toDouble() > g.toDouble() * 1.35 && rr.toDouble() > bl.toDouble() * 1.35) red++
            n++
        }
        return red.toDouble() / n
    }

    private fun redNeighbourSupport(b: Bitmap,x:Int,y:Int):Double{
        var hit=0; var total=0
        for(dy in -2..2) for(dx in -2..2){
            val px=(x+dx).coerceIn(0,b.width-1)
            val py=(y+dy).coerceIn(0,b.height-1)
            val cc=b.getPixel(px,py)
            val r=(cc shr 16) and 255; val g=(cc shr 8) and 255; val bl=cc and 255
            if(r.toDouble()>145.0 && r.toDouble()>g.toDouble()*1.16 && r.toDouble()>bl.toDouble()*1.16) hit++
            total++
        }
        return hit.toDouble()/max(1,total)
    }

    private fun trackPlane(b: Bitmap, t: Long, frameNo: Int): PlaneState {
        val local = !lastX.isNaN() && frameNo - lastPlaneScanFrame <= 3
        val step = if(local) 6 else 8
        val x0=(b.width*focusLeft).toInt(); val x1=(b.width*focusRight).toInt()
        val y0=(b.height*focusTop).toInt(); val y1=(b.height*focusBottom).toInt()
        val px=if(local)(lastX+lastDx*max(18f,b.width*0.08f)).toInt() else (x0+x1)/2
        val py=if(local)(lastY+lastDy*max(12f,b.height*0.05f)).toInt() else (y0+y1)/2
        val rx=if(local)(b.width*0.23).toInt() else (x1-x0)
        val ry=if(local)(b.height*0.20).toInt() else (y1-y0)
        val xs=max(x0,px-rx); val xe=min(x1,px+rx); val ys=max(y0,py-ry); val ye=min(y1,py+ry)
        var sx=0.0; var sy=0.0; var weighted=0.0
        for(y in ys until ye step step) for(x in xs until xe step step){
            val cc=b.getPixel(x,y); val r=(cc shr 16) and 255; val g=(cc shr 8) and 255; val bl=cc and 255
            val red=(r-g*1.30).coerceAtLeast(0).toDouble()/255.0
            val bright=((r+g+bl)/3.0)/255.0
            if(r.toDouble()>155.0 && r.toDouble()>g.toDouble()*1.18 && r.toDouble()>bl.toDouble()*1.18){
                val near=redNeighbourSupport(b,x,y)
                val w=(0.35+red*0.85+bright*0.20)*(0.65+near*0.75)
                sx+=x*w; sy+=y*w; weighted+=w
            }
        }
        var found=weighted>4.0
        if(!found && local){
            sx=0.0; sy=0.0; weighted=0.0
            for(y in y0 until y1 step 7) for(x in x0 until x1 step 7){
                val cc=b.getPixel(x,y); val r=(cc shr 16) and 255; val g=(cc shr 8) and 255; val bl=cc and 255
                if(r.toDouble()>155.0 && r.toDouble()>g.toDouble()*1.18 && r.toDouble()>bl.toDouble()*1.18){
                    val near=redNeighbourSupport(b,x,y)
                    val w=(0.8+(r.toDouble()-g.toDouble()).coerceAtLeast(0.0)/255.0)*(0.7+near*0.6)
                    sx+=x*w;sy+=y*w;weighted+=w
                }
            }
            found=weighted>4.0
        }
        if(found) lostPlaneFrames=0 else lostPlaneFrames++
        val x=if(found)(sx/weighted).toFloat() else Float.NaN
        val y=if(found)(sy/weighted).toFloat() else Float.NaN
        var vx=0f; var vy=0f; var acc=0f
        if(found && !lastX.isNaN() && lastT>0){
            val dt=((t-lastT).coerceAtLeast(1))/1000f
            vx=(x-lastX)/dt; vy=(y-lastY)/dt
            val s=sqrt(vx*vx+vy*vy); acc=(s-lastV)/dt; lastV=s
            val dx=x-lastX; val dy=y-lastY; val norm=sqrt(dx*dx+dy*dy)
            if(norm>0.35f){
                val ndx=dx/norm; val ndy=dy/norm
                if(lastDx!=0f||lastDy!=0f){val agreement=((ndx*lastDx+ndy*lastDy)+1f)*0.5f; movementConsistency=movementConsistency*0.58+agreement*0.42}
                else movementConsistency=0.5
                lastDx=ndx; lastDy=ndy
            }
        }
        if(found){lastX=x;lastY=y;lastT=t;lastPlaneScanFrame=frameNo}
        else if(lostPlaneFrames>=2){movementConsistency*=0.35;lastDx=0f;lastDy=0f;lastV=0f}
        return PlaneState(found,x,y,vx,vy,acc)
    }
}