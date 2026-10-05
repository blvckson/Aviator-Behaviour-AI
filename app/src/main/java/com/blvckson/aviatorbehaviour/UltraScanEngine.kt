package com.blvckson.aviatorbehaviour
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

class UltraScanEngine{
 private var previous:Bitmap?=null
 private var lastX=Float.NaN; private var lastY=Float.NaN; private var lastV=0f; private var lastT=0L
 fun inspect(frame:Bitmap,now:Long):List<VisualEvent>{
  val out=ArrayList<VisualEvent>(); val p=previous
  if(p!=null && p.width==frame.width && p.height==frame.height){
   val change=sampleChange(p,frame)
   if(change>0.025)out.add(VisualEvent(now,"FRAME_CHANGE",change,"visual change"))
   val plane=trackPlane(frame,now)
   if(plane.found){
    val speed=sqrt(plane.vx*plane.vx+plane.vy*plane.vy)
    if(speed>8 || abs(plane.acceleration)>40)out.add(VisualEvent(now,"PLANE_MOTION",speed.toDouble(),"x="+plane.x+",y="+plane.y+",acc="+plane.acceleration))
   }else if(!lastX.isNaN())out.add(VisualEvent(now,"PLANE_DISAPPEAR",1.0,"possible round transition"))
   if(change>0.20)out.add(VisualEvent(now,"ULTRAWATCH",change,"sudden transition"))
  }
  previous=frame.copy(Bitmap.Config.ARGB_8888,true); return out
 }
 private fun sampleChange(a:Bitmap,b:Bitmap):Double{
  val sx=12;val sy=12;var total=0L;var n=0
  for(j in 0 until sy)for(i in 0 until sx){
   val x=i*(b.width-1)/(sx-1);val y=j*(b.height-1)/(sy-1);val ca=a.getPixel(x,y);val cb=b.getPixel(x,y)
   total+=abs(((ca shr 16) and 255)-((cb shr 16) and 255)).toLong()
   total+=abs(((ca shr 8) and 255)-((cb shr 8) and 255)).toLong();total+=abs((ca and 255)-(cb and 255)).toLong();n+=3
  }
  return total.toDouble()/(n*255.0)
 }
 private fun trackPlane(b:Bitmap,t:Long):PlaneState{
  var sx=0.0;var sy=0.0;var n=0;val step=6
  for(y in 0 until b.height step step)for(x in 0 until b.width step step){
   val c=b.getPixel(x,y);val r=(c shr 16) and 255;val g=(c shr 8) and 255;val bl=c and 255
   if(r>170 && r>g*1.25 && r>bl*1.25){sx+=x;sy+=y;n++}
  }
  val found=n>4;val x=if(found)(sx/n).toFloat() else Float.NaN;val y=if(found)(sy/n).toFloat() else Float.NaN
  var vx=0f;var vy=0f;var acc=0f
  if(found && !lastX.isNaN() && lastT>0){val dt=((t-lastT).coerceAtLeast(1))/1000f;vx=(x-lastX)/dt;vy=(y-lastY)/dt;val s=sqrt(vx*vx+vy*vy);acc=(s-lastV)/dt;lastV=s}
  if(found){lastX=x;lastY=y;lastT=t}
  return PlaneState(found,x,y,vx,vy,acc)
 }
}