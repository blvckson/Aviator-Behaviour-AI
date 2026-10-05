package com.blvckson.aviatorbehaviour
import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import android.graphics.PixelFormat

class ScreenMonitorService:Service(){
 private var projection:MediaProjection?=null;private var reader:ImageReader?=null;private val engine=UltraScanEngine();private val handler=Handler(Looper.getMainLooper())
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val code=intent?.getIntExtra("resultCode",-1)?:-1;val data=intent?.getParcelableExtra<Intent>("data")?:return START_NOT_STICKY
  projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code,data)
  startForeground(7,notification());startCapture();return START_STICKY
 }
 private fun startCapture(){
  val wm=getSystemService(WINDOW_SERVICE) as WindowManager;val m=resources.displayMetrics;val w=m.widthPixels;val h=m.heightPixels
  reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
  reader!!.setOnImageAvailableListener({r->
   val image=r.acquireLatestImage()?:return@setOnImageAvailableListener
   try{
    val buf=image.planes[0].buffer;val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);buf.rewind();bmp.copyPixelsFromBuffer(buf)
    engine.inspect(bmp,System.currentTimeMillis());bmp.recycle()
   }finally{image.close()}
  },handler)
  projection!!.createVirtualDisplay("UltraScan",w,h,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
 }
 private fun notification():Notification{
  val ch=NotificationChannel("scan","UltraScan",NotificationManager.IMPORTANCE_LOW);(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
  return Notification.Builder(this,"scan").setContentTitle("Aviator Behaviour AI").setContentText("UltraScan monitoring active").setSmallIcon(android.R.drawable.ic_menu_view).build()
 }
 override fun onDestroy(){reader?.close();projection?.stop();super.onDestroy()}
 override fun onBind(i:Intent?)=null
}