package com.blvckson.aviatorbehaviour

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.MenuInflater
import android.widget.*
import android.view.MenuItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity: Activity() {
    private val requestCode=9001
    private lateinit var status:TextView
    private lateinit var database:BehaviourDatabase
    private val timeFormat=SimpleDateFormat("HH:mm:ss",Locale.US)

    override fun onCreate(b:Bundle?){
        super.onCreate(b); setContentView(R.layout.activity_main)
        status=findViewById(R.id.status); database=BehaviourDatabase(this)
        findViewById<Button>(R.id.start).setOnClickListener{
            if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));status.text="Allow display over other apps, then press START again.";return@setOnClickListener}
            val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(),requestCode)
        }
        findViewById<Button>(R.id.stop).setOnClickListener{stopService(Intent(this,ScreenMonitorService::class.java));status.text="Stopped."}
        findViewById<ImageButton>(R.id.menu_button).setOnClickListener{view->
            val popup=PopupMenu(this,view); MenuInflater(this).inflate(R.menu.behaviour_menu,popup.menu)
            popup.setOnMenuItemClickListener{item->
                when(item.itemId){
                    R.id.menu_all_behaviour->{showStoredBehaviours(false);true}
                    R.id.menu_pre_fly->{showStoredBehaviours(true);true}
                    R.id.menu_analyser->{showEndAnalyser();true}
                    R.id.menu_clear->{confirmClear();true}
                    else->false
                }
            }; popup.show()
        }
    }

    private fun showStoredBehaviours(preFlyOnly:Boolean){
        val records=if(preFlyOnly)database.latestPreFlyAway()else database.latest()
        val title=if(preFlyOnly)"PRE-FLY-AWAY FOCUS"else"STORED ROUND BEHAVIOUR"
        if(records.isEmpty()){AlertDialog.Builder(this).setTitle(title).setMessage("No completed round behaviour has been stored yet.").setPositiveButton("OK",null).show();return}
        val overall=BehaviourAnalyser().analyse(database.latest(200)).overallPreFlySimilarity
        val text=StringBuilder()
        if(preFlyOnly)text.append("OVERALL PRE-FLY-AWAY SIMILARITY: ").append(pct(overall)).append("\n")
            .append("Speed and red-multiplier state are excluded; plane movement/trajectory remains included.\n\n")
        for(r in records){
            text.append("Round ").append(r.round).append("  •  ").append(timeFormat.format(Date(r.time))).append("\n")
                .append("Similarity: ").append(pct(r.maxSimilarity)).append("   Avg: ").append(pct(r.avgSimilarity)).append("\n")
                .append("Plane motion: ").append(number(r.maxPlaneMotion)).append("   Visual change: ").append(pct(r.maxVisualChange)).append("\n")
                .append("UltraWatch: ").append(pct(r.maxUltraWatch)).append("\n")
                .append("ENDING MULTIPLIER: ").append(if(r.endingMultiplier.isBlank())"Not captured" else r.endingMultiplier).append("\n")
            if(preFlyOnly)text.append("◆ PRE-FLY-AWAY AREA\n")
                .append("Similarity: ").append(pct(r.preFlyAwayMaxSimilarity)).append("   Visual: ").append(pct(r.preFlyAwayMaxVisualChange)).append("\n")
                .append("Plane motion: ").append(number(r.preFlyAwayMaxPlaneMotion)).append("   UltraWatch: ").append(pct(r.preFlyAwayMaxUltraWatch)).append("\n")
            text.append("\n")
        }
        AlertDialog.Builder(this).setTitle(title+" ("+records.size+")").setMessage(text.toString()).setPositiveButton("CLOSE",null).show()
    }

    private fun showEndAnalyser(){
        val records=database.latest(200)
        if(records.isEmpty()){AlertDialog.Builder(this).setTitle("END-OF-ROUND BEHAVIOUR ANALYSER").setMessage("The analyser needs completed stored rounds first.").setPositiveButton("OK",null).show();return}
        val a=BehaviourAnalyser().analyse(records)
        val msg=StringBuilder()
            .append("Rounds analysed: ").append(a.rounds).append("\n\n")
            .append("OVERALL PRE-FLY-AWAY SIMILARITY: ").append(pct(a.overallPreFlySimilarity)).append("\n")
            .append("Movement/trajectory, visual transition, multiplier visual behaviour, UltraWatch and stability are included.\n")
            .append("Speed and red-multiplier state are excluded from this percentage.\n\n")
            .append("OVERALL ENDING DIFFERENCE: ").append(pct(a.endingDifferenceScore)).append("\n")
            .append("Compares the final pre-fly-away window with the rest of each stored round.\n\n")
            .append("SIMILARITY\nRound average: ").append(pct(a.averageSimilarity))
            .append("\nPre-fly-away: ").append(pct(a.averagePreFlySimilarity)).append("\nChange: ").append(signed(a.similarityRise)).append("\n\n")
            .append("VISUAL TRANSITION\nPre-fly-away: ").append(pct(a.averagePreFlyVisualChange)).append("\nChange: ").append(signed(a.visualRise)).append("\n\n")
            .append("PLANE BEHAVIOUR\nPre-fly-away motion: ").append(number(a.averagePreFlyPlaneMotion)).append("\nChange: ").append(signed(a.planeMotionRise)).append("\n\n")
            .append("ULTRAWATCH\nPre-fly-away: ").append(pct(a.averagePreFlyUltraWatch)).append("\nChange: ").append(signed(a.ultraWatchRise)).append("\n\n")
            .append("STRONGEST STORED ENDING: Round ").append(a.strongestRound).append("\n\n")
            .append("Historical sequence agreement: ").append(pct(a.sequenceAgreement)).append("\n")
            .append("Rounds with end markers: ").append(pct(a.markerAgreement)).append("\n")
            .append("First detected change phase: ").append(pct(a.firstChangePhase*100.0)).append("\n")
            .append("Strongest change phase: ").append(pct(a.endingChangePhase*100.0)).append("\n\n")
            .append("The analyser compares late behaviour against each round’s earlier baseline, then checks repeated transitions across stored rounds. Observed behaviour only; no guaranteed outcome.")
        AlertDialog.Builder(this).setTitle("END-OF-ROUND ANALYSER").setMessage(msg.toString()).setPositiveButton("CLOSE",null).show()
    }

    private fun signed(value:Double):String=(if(value>=0)"+"else"")+"%.1f".format(Locale.US,value)+" pts"
    private fun confirmClear(){
        AlertDialog.Builder(this).setTitle("Clear behaviour storage?")
            .setMessage("This removes the stored round behaviour and the separate pre-fly-away records.")
            .setNegativeButton("CANCEL",null).setPositiveButton("CLEAR"){_,_->database.clearAll();Toast.makeText(this,"Behaviour storage cleared.",Toast.LENGTH_SHORT).show()}.show()
    }
    private fun pct(value:Double):String="%.0f%%".format(Locale.US,value.coerceIn(0.0,100.0))
    private fun number(value:Double):String="%.1f".format(Locale.US,value)

    private fun showBehaviourMenu(anchor:android.view.View){
        val popup=PopupMenu(this,anchor); MenuInflater(this).inflate(R.menu.behaviour_menu,popup.menu)
        popup.setOnMenuItemClickListener{item:MenuItem->when(item.itemId){
            R.id.menu_all_behaviour->{showStored(false);true};R.id.menu_pre_fly->{showStored(true);true}
            R.id.menu_analyser->{showAnalysis();true};R.id.menu_clear->{BehaviourDatabase(this).clearAll();status.text="Behaviour storage cleared.";true};else->false}}
        popup.show()
    }
    private fun showStored(preOnly:Boolean){
        val rows=if(preOnly)BehaviourDatabase(this).latestPreFlyAway(40)else BehaviourDatabase(this).latest(40)
        val text=if(rows.isEmpty())"No stored behaviour rounds yet."else rows.joinToString("\n\n"){r->"Round "+r.round+": samples="+r.similaritySamples+"\npre-fly similarity="+String.format("%.1f",r.preFlyAwayMaxSimilarity)+"  visual="+String.format("%.3f",r.preFlyAwayMaxVisualChange)+"  plane="+String.format("%.1f",r.preFlyAwayMaxPlaneMotion)+"  UltraWatch="+String.format("%.1f",r.preFlyAwayMaxUltraWatch)+"\nmarkers="+r.endingMarkers+"\nending multiplier="+if(r.endingMultiplier.isBlank())"not captured"else r.endingMultiplier}
        AlertDialog.Builder(this).setTitle(if(preOnly)"Pre-Fly-Away Focus"else"Stored Round Behaviour").setMessage(text).setPositiveButton("OK",null).show()
    }
    private fun showAnalysis(){
        val a=BehaviourAnalyser().analyse(BehaviourDatabase(this).latest(80))
        val msg=if(a.rounds==0)"No stored rounds yet. Run UltraScan through several completed rounds first."else
            "Rounds analysed: "+a.rounds+"\nOverall pre-fly-away similarity: "+String.format("%.1f",a.overallPreFlySimilarity)+"%\nEnd-behaviour difference: "+String.format("%.1f",a.endingDifferenceScore)+"%\nHistorical sequence agreement: "+String.format("%.1f",a.sequenceAgreement)+"%\nRounds with ending markers: "+String.format("%.1f",a.markerAgreement)+"%\nFirst change phase: "+String.format("%.0f",a.firstChangePhase*100)+"% of round\nStrongest change phase: "+String.format("%.0f",a.endingChangePhase*100)+"% of round\nSimilarity rise: "+String.format("%.1f",a.similarityRise)+"\nVisual rise: "+String.format("%.1f",a.visualRise)+"\nPlane behaviour rise: "+String.format("%.1f",a.planeMotionRise)+"\nUltraWatch rise: "+String.format("%.1f",a.ultraWatchRise)+"\nStrongest stored ending: round "+a.strongestRound+"\n\nSpeed and red-multiplier state are excluded from the overall similarity; movement/trajectory remains included."
        AlertDialog.Builder(this).setTitle("Super End-Behaviour Analyser").setMessage(msg).setPositiveButton("OK",null).show()
    }

    override fun onActivityResult(rc:Int,result:Int,data:Intent?){
        super.onActivityResult(rc,result,data)
        if(rc==requestCode&&data!=null){
            val i=Intent(this,ScreenMonitorService::class.java).apply{putExtra("resultCode",result);putExtra("data",data)}
            startService(i); status.text="UltraScan running."
        }
    }
    override fun onDestroy(){database.close();super.onDestroy()}
}