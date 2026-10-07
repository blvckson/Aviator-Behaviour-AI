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
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

class ScreenMonitorService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private val engine = UltraScanEngine()
    private val multiplierReader = MultiplierReader()
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
    private var currentEndingMultiplier = ""
    private var lastMultiplierReadAt = 0L
    private var pendingEndingRound = 0
    private var pendingEndingUntil = 0L
    private var lastPlaneX = 0.0
    private var lastPlaneY = 0.0
    private val preFlyAwayWindowMs = 1800L
    private val recentSamples = ArrayDeque<BehaviourSample>()
    private val sequenceSamples = ArrayList<BehaviourSample>()
    private var lastStoredSampleAt = 0L
    private val sampleIntervalMs = 100L
    private val endingMarkers = LinkedHashSet<String>()

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
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code,data)
        startForeground(7,notification())
        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        if(reader!=null)return
        val m=resources.displayMetrics; val w=m.widthPixels; val h=m.heightPixels
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3)
        reader!!.setOnImageAvailableListener({r->
            val image=r.acquireLatestImage()?:return@setOnImageAvailableListener
            try{
                val plane=image.planes[0]; val rowStride=plane.rowStride; val pixelStride=plane.pixelStride
                val rowPadding=rowStride-pixelStride*w; val paddedWidth=w+rowPadding/pixelStride
                val bmp=Bitmap.createBitmap(paddedWidth,h,Bitmap.Config.ARGB_8888)
                plane.buffer.rewind(); bmp.copyPixelsFromBuffer(plane.buffer)
                val frame=if(paddedWidth==w)bmp else Bitmap.createBitmap(bmp,0,0,w,h)
                val now=System.currentTimeMillis()
                val events=engine.inspect(frame,now)
                if(roundActive && (now-lastMultiplierReadAt>=80L || events.any{it.type=="ENDING_RED_VISUAL"||it.type=="ENDING_STATE_CLUSTER"||it.type=="PLANE_DISAPPEAR"||it.type=="ULTRAWATCH"||it.type=="PRE_FLY_AWAY_MATCH"})){
                    lastMultiplierReadAt=now
                    multiplierReader.inspect(frame){value->
                        if(roundActive) currentEndingMultiplier=value
                        if(pendingEndingRound>0 && System.currentTimeMillis()<=pendingEndingUntil)
                            database.updateEndingMultiplier(pendingEndingRound,value)
                    }
                }
                recordEvents(events,now)
                if(frame!==bmp)bmp.recycle()
                frame.recycle()
                if(events.isNotEmpty())publish(events)else publishStatus(engine.currentSimilarity())
            }finally{image.close()}
        },captureHandler)
        projection!!.createVirtualDisplay("UltraScan",w,h,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,captureHandler)
    }

    private fun recordEvents(events:List<VisualEvent>,now:Long){
        for(event in events.filter{it.type=="BEHAVIOUR_SIMILARITY"||it.type=="PRE_FLY_AWAY_MATCH"}){
            maxSimilarity=max(maxSimilarity,event.score); similaritySum+=event.score; similaritySamples++
        }
        var sim=0.0; var visual=0.0; var plane=0.0; var ultra=0.0; var red=0.0; var stable=0.0
        for(event in events)when(event.type){
            "FRAME_CHANGE"->{maxVisualChange=max(maxVisualChange,event.score*100.0);visual=max(visual,event.score*100.0)}
            "MULTIPLIER_VISUAL_CHANGE"->visual=max(visual,event.score*100.0)
            "PLANE_MOTION"->{maxPlaneMotion=max(maxPlaneMotion,event.score);plane=max(plane,event.score);lastPlaneX=coordinate(event.detail,"x",lastPlaneX);lastPlaneY=coordinate(event.detail,"y",lastPlaneY)}
            "ULTRAWATCH"->{maxUltraWatch=max(maxUltraWatch,event.score*100.0);ultra=max(ultra,event.score*100.0)}
            "PRE_FLY_AWAY_DETECTED"->{maxUltraWatch=max(maxUltraWatch,event.score);ultra=max(ultra,event.score)}
            "BEHAVIOUR_SIMILARITY","PRE_FLY_AWAY_MATCH"->sim=max(sim,event.score)
            "ENDING_RED_VISUAL"->red=max(red,event.score)
            "MULTIPLIER_VISUAL_STABLE"->stable=1.0
            "ENDING_STATE_CLUSTER"->{red=max(red,event.score);stable=1.0}
        }
        for(event in events)when(event.type){
            "ENDING_RED_VISUAL"->endingMarkers.add("RED_MULTIPLIER_AREA")
            "MULTIPLIER_VISUAL_STABLE"->endingMarkers.add("MULTIPLIER_VISUALLY_CONSTANT")
            "ENDING_STATE_CLUSTER"->endingMarkers.add("ENDING_VISUAL_CLUSTER")
            "PLANE_DISAPPEAR"->endingMarkers.add("PLANE_DISAPPEAR")
        }
        val sample=BehaviourSample(now,sim,visual,plane,ultra,red,stable,lastPlaneX,lastPlaneY)
        recentSamples.addLast(sample)
        while(recentSamples.isNotEmpty()&&now-recentSamples.peekFirst().time>preFlyAwayWindowMs)recentSamples.removeFirst()
        if(roundActive&&(lastStoredSampleAt==0L||now-lastStoredSampleAt>=sampleIntervalMs)){sequenceSamples.add(sample);lastStoredSampleAt=now}
        if(events.any{it.type=="PLANE_DISAPPEAR"||it.type=="ENDING_STATE_CLUSTER"}&&roundActive){
            for(x in recentSamples){preMaxSimilarity=max(preMaxSimilarity,x.similarity);preMaxVisualChange=max(preMaxVisualChange,x.visualChange);preMaxPlaneMotion=max(preMaxPlaneMotion,x.planeMotion);preMaxUltraWatch=max(preMaxUltraWatch,x.ultraWatch)}
            pendingEndingRound=roundNumber; pendingEndingUntil=now+900L
            saveRound(now); roundActive=false; recentSamples.clear(); sequenceSamples.clear(); endingMarkers.clear(); lastStoredSampleAt=0L
        }
        if(events.any{it.type=="PLANE_MOTION"}||engine.lastPlaneSeenAt()==now)if(!roundActive){
            roundActive=true; roundNumber=database.nextRoundNumber()
            similaritySum=0.0; similaritySamples=0; maxSimilarity=0.0; maxVisualChange=0.0; maxPlaneMotion=0.0; maxUltraWatch=0.0
            preMaxSimilarity=0.0; preMaxVisualChange=0.0; preMaxPlaneMotion=0.0; preMaxUltraWatch=0.0
            currentEndingMultiplier=""; lastMultiplierReadAt=0L; lastPlaneX=0.0; lastPlaneY=0.0
            sequenceSamples.clear(); endingMarkers.clear(); lastStoredSampleAt=0L
        }
    }

    private fun coordinate(detail:String,key:String,fallback:Double):Double{
        val marker="$key="
        val start=detail.indexOf(marker); if(start<0)return fallback
        val end=detail.indexOf(',',start+marker.length)
        val raw=if(end>=0)detail.substring(start+marker.length,end)else detail.substring(start+marker.length)
        return raw.toDoubleOrNull()?:fallback
    }

    private fun saveRound(now:Long){
        val avg=if(similaritySamples>0)similaritySum/similaritySamples else 0.0
        val seq=sequenceSamples.joinToString(";"){x->"%.3f,%.3f,%.3f,%.3f,%.3f,%.0f,%.3f,%.2f,%.2f".format(Locale.US,x.similarity,x.visualChange,x.planeMotion,x.ultraWatch,x.red,x.stable,x.time.toDouble()/1000.0,x.planeX,x.planeY)}
        database.saveRound(StoredBehaviour(roundNumber,now,maxSimilarity,avg,similaritySamples,maxVisualChange,maxPlaneMotion,maxUltraWatch,preMaxSimilarity,preMaxVisualChange,preMaxPlaneMotion,preMaxUltraWatch,seq,endingMarkers.joinToString("|"),currentEndingMultiplier))
    }

    private fun publish(events:List<VisualEvent>){
        val cashOut=events.lastOrNull{it.type=="PRE_FLY_AWAY_DETECTED"}
        if(cashOut!=null){
            uiHandler.post{
                overlay?.text=dotStatus(true)+"  CASH OUT NOW — PRE-FLY-AWAY DETECTED\\n${cashOut.detail}"
                overlay?.setBackgroundColor(0xFFE53935.toInt())
                overlay?.setTextColor(0xFFFFFFFF.toInt())
            }
            return
        }
        val match=events.lastOrNull{it.type=="PRE_FLY_AWAY_MATCH"||it.type=="BEHAVIOUR_SIMILARITY"}
        if(match!=null){
            val high=match.type=="PRE_FLY_AWAY_MATCH"||match.score>=82.0; val strong=match.score>=65.0
            val label=when{high->"BEHAVIOUR: %.0f%% HIGH MATCH".format(match.score);strong->"BEHAVIOUR: %.0f%% STRONG MATCH".format(match.score);else->"BEHAVIOUR: %.0f%% MATCH".format(match.score)}
            uiHandler.post{overlay?.text=dotStatus(false)+"  "+label; overlay?.setBackgroundColor(0xCC202124.toInt()); overlay?.setTextColor(0xFFFFFFFF.toInt())}
        }else publishStatus(engine.currentSimilarity())
    }

    private fun publishStatus(score:Double){uiHandler.post{overlay?.text=dotStatus(false)+"  "+(if(score>0.45)"BEHAVIOUR: %.0f%% MATCH".format(score*100.0)else "BEHAVIOUR: MONITORING"); overlay?.setBackgroundColor(0xCC202124.toInt()); overlay?.setTextColor(0xFFFFFFFF.toInt())}}

    private fun dotStatus(preFlyAway:Boolean):android.text.SpannableString{
        val text=if(preFlyAway)"●  ●" else "●"
        val s=android.text.SpannableString(text)
        s.setSpan(android.text.style.ForegroundColorSpan(0xFF00C853.toInt()),0,1,android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if(preFlyAway)s.setSpan(android.text.style.ForegroundColorSpan(0xFFE53935.toInt()),3,4,android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun showOverlay(){
        windowManager=getSystemService(WINDOW_SERVICE) as WindowManager
        val tv=TextView(this); tv.text=dotStatus(false).toString()+"  BEHAVIOUR: MONITORING"; tv.textSize=13f
        tv.setTextColor(0xFFFFFFFF.toInt()); tv.setBackgroundColor(0xCC202124.toInt()); tv.setPadding(18,10,18,10)
        val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.START; lp.x=overlayX; lp.y=overlayY
        var downX=0f; var downY=0f; var startX=0; var startY=0
        tv.setOnTouchListener{_,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;startX=lp.x;startY=lp.y;true}
            MotionEvent.ACTION_MOVE->{lp.x=startX+(e.rawX-downX).toInt();lp.y=startY+(e.rawY-downY).toInt();windowManager?.updateViewLayout(tv,lp);true}
            else->true
        }}
        windowManager?.addView(tv,lp); overlay=tv
    }

    private fun notification():Notification{
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("scan","UltraScan",NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this,"scan").setContentTitle("Aviator Behaviour AI").setContentText("UltraScan monitoring active").setSmallIcon(android.R.drawable.ic_menu_view).build()
    }

    override fun onDestroy(){
        reader?.close(); reader=null; projection?.stop(); overlay?.let{windowManager?.removeView(it)}; overlay=null
        multiplierReader.close(); captureThread.quitSafely(); database.close(); super.onDestroy()
    }
    override fun onBind(intent:Intent?)=null
}