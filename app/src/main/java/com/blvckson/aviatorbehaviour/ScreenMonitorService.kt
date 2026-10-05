package com.blvckson.aviatorbehaviour

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.TextView
import kotlin.math.max
import java.util.ArrayDeque
import java.util.Locale

class ScreenMonitorService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private val engine = UltraScanEngine()
    private lateinit var database: BehaviourDatabase
    private val captureThread = HandlerThread("UltraScanCapture", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var captureHandler: Handler
    private lateinit var uiHandler: Handler
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null
    private var overlayX = 24
    private var overlayY = 80

    private var roundActive = false
    private var roundNumber = 0
    private var similaritySum = 0.0
    private var similaritySamples = 0
    private var maxSimilarity = 0.0
    private var maxVisualChange = 0.0
    private var maxPlaneMotion = 0.0
    private var maxUltraWatch = 0.0
    private var preMaxSimilarity = 0.0
    private var preMaxVisualChange = 0.0
    private var preMaxPlaneMotion = 0.0
    private var preMaxUltraWatch = 0.0
    private val preFlyAwayWindowMs = 1800L
    private val recentSamples = ArrayDeque<BehaviourSample>()
    private val sequenceSamples = ArrayList<BehaviourSample>()
    private var lastStoredSampleAt = 0L
    private val sampleIntervalMs = 100L
    private val endingMarkers = LinkedHashSet<String>()

    private data class BehaviourSample(
        val time: Long,
        val similarity: Double,
        val visualChange: Double,
        val planeMotion: Double,
        val ultraWatch: Double
    )

    override fun onCreate() {
        super.onCreate()
        database = BehaviourDatabase(this)
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        uiHandler = Handler(Looper.getMainLooper())
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
            ?: return START_NOT_STICKY
        projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(code, data)
        startForeground(7, notification())
        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        if (reader != null) return
        val m = resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * w
                val paddedWidth = w + rowPadding / pixelStride
                val bmp = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
                plane.buffer.rewind()
                bmp.copyPixelsFromBuffer(plane.buffer)
                val frame = if (paddedWidth == w) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
                val now = System.currentTimeMillis()
                val events = engine.inspect(frame, now)
                recordEvents(events, now)
                if (frame !== bmp) bmp.recycle()
                frame.recycle()
                if (events.isNotEmpty()) publish(events)
                else publishStatus(engine.currentSimilarity())
            } finally {
                image.close()
            }
        }, captureHandler)

        projection!!.createVirtualDisplay(
            "UltraScan", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, captureHandler
        )
    }

    private fun recordEvents(events: List<VisualEvent>, now: Long) {
        val similarityEvents = events.filter { it.type == "BEHAVIOUR_SIMILARITY" || it.type == "PRE_FLY_AWAY_MATCH" }
        for (event in similarityEvents) { maxSimilarity=max(maxSimilarity,event.score); similaritySum+=event.score; similaritySamples++ }
        var sim=0.0; var visual=0.0; var plane=0.0; var ultra=0.0; var red=0.0; var stable=0.0
        for(event in events) when(event.type){
            "FRAME_CHANGE" -> { maxVisualChange=max(maxVisualChange,event.score*100.0); visual=max(visual,event.score*100.0) }
            "MULTIPLIER_VISUAL_CHANGE" -> visual=max(visual,event.score*100.0)
            "PLANE_MOTION" -> { maxPlaneMotion=max(maxPlaneMotion,event.score); plane=max(plane,event.score) }
            "ULTRAWATCH" -> { maxUltraWatch=max(maxUltraWatch,event.score*100.0); ultra=max(ultra,event.score*100.0) }
            "BEHAVIOUR_SIMILARITY","PRE_FLY_AWAY_MATCH" -> sim=max(sim,event.score)
            "ENDING_RED_VISUAL" -> red=max(red,event.score)
            "MULTIPLIER_VISUAL_STABLE" -> stable=1.0
            "ENDING_STATE_CLUSTER" -> { red=max(red,event.score); stable=1.0 }
        }
        for(event in events) when(event.type){
            "ENDING_RED_VISUAL" -> endingMarkers.add("RED_MULTIPLIER_AREA")
            "MULTIPLIER_VISUAL_STABLE" -> endingMarkers.add("MULTIPLIER_VISUALLY_CONSTANT")
            "ENDING_STATE_CLUSTER" -> endingMarkers.add("ENDING_VISUAL_CLUSTER")
            "PLANE_DISAPPEAR" -> endingMarkers.add("PLANE_DISAPPEAR")
        }
        val sample=BehaviourSample(now,sim,visual,plane,ultra,red,stable)
        recentSamples.addLast(sample)
        while(recentSamples.isNotEmpty() && now-recentSamples.peekFirst().time>preFlyAwayWindowMs) recentSamples.removeFirst()
        if(roundActive && (lastStoredSampleAt==0L || now-lastStoredSampleAt>=sampleIntervalMs)){ sequenceSamples.add(sample); lastStoredSampleAt=now }
        val disappeared=events.any{it.type=="PLANE_DISAPPEAR"}
        if(disappeared && roundActive){
            for(s in recentSamples){preMaxSimilarity=max(preMaxSimilarity,s.similarity);preMaxVisualChange=max(preMaxVisualChange,s.visualChange);preMaxPlaneMotion=max(preMaxPlaneMotion,s.planeMotion);preMaxUltraWatch=max(preMaxUltraWatch,s.ultraWatch)}
            saveRound(now); roundActive=false; recentSamples.clear(); sequenceSamples.clear(); endingMarkers.clear(); lastStoredSampleAt=0L
        }
        if(events.any{it.type=="PLANE_MOTION"} || engine.lastPlaneSeenAt()==now){
            if(!roundActive){
                roundActive=true; roundNumber=database.nextRoundNumber()
                similaritySum=0.0; similaritySamples=0; maxSimilarity=0.0; maxVisualChange=0.0; maxPlaneMotion=0.0; maxUltraWatch=0.0
                preMaxSimilarity=0.0;preMaxVisualChange=0.0;preMaxPlaneMotion=0.0;preMaxUltraWatch=0.0
                sequenceSamples.clear();endingMarkers.clear();lastStoredSampleAt=0L
            }
        }
    }

    private fun saveRound(now:Long){
        val avg=if(similaritySamples>0) similaritySum/similaritySamples else 0.0
        val seq=sequenceSamples.joinToString(";"){s->"%.3f,%.3f,%.3f,%.3f,%.3f,%.0f,%.0f".format(Locale.US,s.similarity,s.visualChange,s.planeMotion,s.ultraWatch,s.red,s.stable)}
        database.saveRound(StoredBehaviour(roundNumber,now,maxSimilarity,avg,similaritySamples,maxVisualChange,maxPlaneMotion,maxUltraWatch,preMaxSimilarity,preMaxVisualChange,preMaxPlaneMotion,preMaxUltraWatch,seq,endingMarkers.joinToString("|")))
    }
}