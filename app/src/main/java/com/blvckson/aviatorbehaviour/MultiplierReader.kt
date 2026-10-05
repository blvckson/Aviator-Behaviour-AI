package com.blvckson.aviatorbehaviour

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

class MultiplierReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false
    @Volatile var lastReading: String = ""
        private set

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
            pixels[i]=if(r>90&&r>g*1.22&&r>b*1.22)Color.WHITE else Color.BLACK
        }
        redOnly.setPixels(pixels,0,w,0,0,w,h); crop.recycle()
        recognizer.process(InputImage.fromBitmap(redOnly,0))
            .addOnSuccessListener{ text->
                val candidate=extract(text.text)
                if(candidate.isNotEmpty()){lastReading=candidate;onReading(candidate)}
            }
            .addOnCompleteListener{redOnly.recycle();busy=false}
    }

    private fun extract(raw:String):String{
        val matcher=Pattern.compile("(\\d{1,7}(?:[.,]\\d{1,4})?)\\s*[xX]?").matcher(raw.replace(',','.'))
        var best=-1.0
        while(matcher.find()){
            val v=matcher.group(1)?.toDoubleOrNull()?:continue
            if(v>=1.0&&v<=10000000.0&&v>best)best=v
        }
        return if(best<0) "" else "%.2fx".format(Locale.US,best)
    }
    fun close(){recognizer.close()}
}