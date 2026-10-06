package com.blvckson.aviatorbehaviour

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max

class MultiplierReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false
    @Volatile var lastReading: String = ""
        private set
    private var lastValue = Double.NaN
    private var lastAcceptedAt = 0L

    fun inspect(frame: Bitmap, onReading: (String) -> Unit) {
        if (busy || frame.width < 40 || frame.height < 40) return
        busy = true
        val left=(frame.width*0.18f).toInt(); val top=(frame.height*0.16f).toInt()
        val right=(frame.width*0.82f).toInt(); val bottom=(frame.height*0.60f).toInt()
        val w=max(1,right-left); val h=max(1,bottom-top)
        val crop=Bitmap.createBitmap(frame,left,top,w,h)
        val redOnly=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(w*h); crop.getPixels(pixels,0,w,0,0,w,h)
        for(i in pixels.indices){
            val c=pixels[i]; val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            val dominance=r-max(g,b)
            val bright=(r+g+b)/3
            pixels[i]=if(r>105&&dominance>28&&bright>70)Color.WHITE else Color.BLACK
        }
        redOnly.setPixels(pixels,0,w,0,0,w,h); crop.recycle()
        recognizer.process(InputImage.fromBitmap(redOnly,0))
            .addOnSuccessListener{ text->
                val parsed=extractValue(text.text)
                val now=System.currentTimeMillis()
                if(parsed!=null && accept(parsed,now)){
                    val candidate="%.2fx".format(Locale.US,parsed)
                    lastReading=candidate; onReading(candidate)
                }
            }
            .addOnCompleteListener{redOnly.recycle();busy=false}
    }

    private fun extractValue(raw:String):Double?{
        val cleaned=raw.replace(',','.').replace('O','0').replace('o','0').replace('I','1').replace('l','1')
        val matcher=Pattern.compile("(\\d{1,7}(?:\\.\\d{1,4})?)\\s*[xX]?").matcher(cleaned)
        var best:Double?=null
        while(matcher.find()){
            val v=matcher.group(1)?.toDoubleOrNull()?:continue
            if(v>=1.0&&v<=10000000.0 && (best==null || v>best!!)) best=v
        }
        return best
    }

    private fun accept(value:Double,now:Long):Boolean{
        if(!value.isFinite() || value<1.0 || value>10000000.0)return false
        if(lastValue.isNaN()) { lastValue=value; lastAcceptedAt=now; return true }
        val age=now-lastAcceptedAt
        val jump=abs(value-lastValue)
        if(age<220L && jump>max(25.0,lastValue*3.5))return false
        lastValue=value; lastAcceptedAt=now
        return true
    }
    fun close(){recognizer.close()}
}