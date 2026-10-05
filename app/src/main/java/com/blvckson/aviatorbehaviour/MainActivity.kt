package com.blvckson.aviatorbehaviour
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity:Activity(){
 private val requestCode=9001
 private lateinit var status:TextView
 override fun onCreate(b:Bundle?){
  super.onCreate(b); setContentView(R.layout.activity_main); status=findViewById(R.id.status)
  findViewById<Button>(R.id.start).setOnClickListener{
   if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));status.text="Allow display over other apps, then press START again.";return@setOnClickListener}
   val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   startActivityForResult(mgr.createScreenCaptureIntent(),requestCode)
  }
  findViewById<Button>(R.id.stop).setOnClickListener{stopService(Intent(this,ScreenMonitorService::class.java));status.text="Stopped."}
 }
 override fun onActivityResult(rc:Int,result:Int,data:Intent?){
  super.onActivityResult(rc,result,data)
  if(rc==requestCode && data!=null){
   val i=Intent(this,ScreenMonitorService::class.java).apply{putExtra("resultCode",result);putExtra("data",data)}
   startService(i);status.text="UltraScan running."
  }
 }
}