package com.blvckson.aviatorbehaviour

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BehaviourAnalysis(
    val rounds:Int, val averageSimilarity:Double, val averagePreFlySimilarity:Double,
    val averagePreFlyVisualChange:Double, val averagePreFlyPlaneMotion:Double,
    val averagePreFlyUltraWatch:Double, val similarityRise:Double, val visualRise:Double,
    val planeMotionRise:Double, val ultraWatchRise:Double, val endingDifferenceScore:Double,
    val strongestRound:Int, val sequenceAgreement:Double, val markerAgreement:Double,
    val firstChangePhase:Double, val endingChangePhase:Double
)

class BehaviourAnalyser {
    data class S(val sim:Double,val visual:Double,val plane:Double,val ultra:Double,val red:Double,val stable:Double,val t:Double)
    fun analyse(records:List<StoredBehaviour>):BehaviourAnalysis{
        if(records.isEmpty()) return BehaviourAnalysis(0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0,0.0,0.0,0.0,1.0)
        fun avg(f:(StoredBehaviour)->Double)=records.map(f).average()
        val baseSimilarity=avg{it.avgSimilarity}; val preSimilarity=avg{it.preFlyAwayMaxSimilarity}
        val baseVisual=avg{it.maxVisualChange}; val preVisual=avg{it.preFlyAwayMaxVisualChange}
        val basePlane=avg{it.maxPlaneMotion}; val prePlane=avg{it.preFlyAwayMaxPlaneMotion}
        val baseUltra=avg{it.maxUltraWatch}; val preUltra=avg{it.preFlyAwayMaxUltraWatch}
        val seq=records.map{parse(it.sequence)}.filter{it.size>=4}
        var agree=0.0; var first=1.0; var last=1.0
        if(seq.isNotEmpty()){
            val phases=seq.map{samples->
                val base=avgVector(samples.take(max(1,samples.size*55/100)))
                val deltas=samples.mapIndexed{idx,s->
                    val d=distance(s,base)
                    idx.toDouble()/max(1,samples.size-1) to d
                }
                val maxD=deltas.maxByOrNull{it.second}?.first ?: 1.0
                val threshold=deltas.map{it.second}.average()+deltas.map{abs(it.second-it.second)}.average()
                val firstChange=deltas.firstOrNull{it.second>threshold*1.15}?.first ?: 1.0
                Pair(firstChange,maxD)
            }
            first=phases.map{it.first}.average()
            last=phases.map{it.second}.average()
            val aligned=seq.map{samples->
                val base=avgVector(samples.take(max(1,samples.size*55/100)))
                samples.takeLast(max(1,samples.size*35/100)).map{distance(it,base)}.average()
            }
            agree=100.0/(1.0+variance(aligned))
        }
        val markerAgreement=100.0*records.count{it.endingMarkers.isNotBlank()}.toDouble()/records.size
        val simRise=preSimilarity-baseSimilarity; val visualRise=preVisual-baseVisual
        val planeRise=prePlane-basePlane; val ultraRise=preUltra-baseUltra
        val endingScore=weightedDifference(simRise,baseSimilarity,visualRise,baseVisual,planeRise,basePlane,ultraRise,baseUltra)
        val strongest=records.maxByOrNull{it.preFlyAwayMaxSimilarity*.30+it.preFlyAwayMaxVisualChange*.20+normalise(it.preFlyAwayMaxPlaneMotion)*.15+it.preFlyAwayMaxUltraWatch*.20+markerScore(it.endingMarkers)*.15}?.round?:0
        return BehaviourAnalysis(records.size,baseSimilarity,preSimilarity,preVisual,prePlane,preUltra,simRise,visualRise,planeRise,ultraRise,endingScore,strongest,agree,markerAgreement,first,last)
    }
    private fun parse(raw:String):List<S>{
        if(raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull{p->val a=p.split(',');if(a.size>=7) S(a[0].toDoubleOrNull()?:0.0,a[1].toDoubleOrNull()?:0.0,a[2].toDoubleOrNull()?:0.0,a[3].toDoubleOrNull()?:0.0,a[4].toDoubleOrNull()?:0.0,a[5].toDoubleOrNull()?:0.0,a[6].toDoubleOrNull()?:0.0)else null}
    }
    private fun avgVector(x:List<S>):S{if(x.isEmpty())return S(0.0,0.0,0.0,0.0,0.0,0.0,0.0);return S(x.map{it.sim}.average(),x.map{it.visual}.average(),x.map{it.plane}.average(),x.map{it.ultra}.average(),x.map{it.red}.average(),x.map{it.stable}.average(),0.0)}
    private fun distance(a:S,b:S):Double{
        fun d(x:Double,y:Double,scale:Double)=min(1.0,abs(x-y)/max(scale,0.0001))
        return d(a.sim,b.sim,100.0)*.22+d(a.visual,b.visual,100.0)*.18+d(a.plane,b.plane,80.0)*.18+d(a.ultra,b.ultra,100.0)*.18+d(a.red,b.red,10.0)*.12+d(a.stable,b.stable,1.0)*.12
    }
    private fun variance(x:List<Double>):Double{if(x.size<2)return 0.0;val m=x.average();return x.map{(it-m)*(it-m)}.average()}
    private fun weightedDifference(sim:Double,sb:Double,visual:Double,vb:Double,plane:Double,pb:Double,ultra:Double,ub:Double):Double{
        fun rel(d:Double,b:Double)=if(b<=.0001)min(1.0,abs(d))else min(1.0,abs(d)/max(b,1.0))
        return (rel(sim,sb)*.28+rel(visual,vb)*.22+rel(plane,pb)*.18+rel(ultra,ub)*.22)*100.0
    }
    private fun normalise(v:Double)=min(100.0,v)/100.0
    private fun markerScore(s:String)=if(s.isBlank())0.0 else min(1.0,s.split('|').size/4.0)
}