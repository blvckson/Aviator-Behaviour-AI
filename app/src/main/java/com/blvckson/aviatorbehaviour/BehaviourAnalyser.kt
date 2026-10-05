package com.blvckson.aviatorbehaviour

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BehaviourAnalysis(
    val rounds: Int,
    val averageSimilarity: Double,
    val averagePreFlySimilarity: Double,
    val averagePreFlyVisualChange: Double,
    val averagePreFlyPlaneMotion: Double,
    val averagePreFlyUltraWatch: Double,
    val similarityRise: Double,
    val visualRise: Double,
    val planeMotionRise: Double,
    val ultraWatchRise: Double,
    val endingDifferenceScore: Double,
    val strongestRound: Int
)

class BehaviourAnalyser {

    fun analyse(records: List<StoredBehaviour>): BehaviourAnalysis {
        if (records.isEmpty()) {
            return BehaviourAnalysis(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        }

        fun avg(selector: (StoredBehaviour) -> Double): Double =
            records.map(selector).average()

        val baseSimilarity = avg { it.avgSimilarity }
        val preSimilarity = avg { it.preFlyAwayMaxSimilarity }
        val baseVisual = avg { it.maxVisualChange }
        val preVisual = avg { it.preFlyAwayMaxVisualChange }
        val basePlane = avg { it.maxPlaneMotion }
        val prePlane = avg { it.preFlyAwayMaxPlaneMotion }
        val baseUltra = avg { it.maxUltraWatch }
        val preUltra = avg { it.preFlyAwayMaxUltraWatch }

        val simRise = preSimilarity - baseSimilarity
        val visualRise = preVisual - baseVisual
        val planeRise = prePlane - basePlane
        val ultraRise = preUltra - baseUltra

        val endingScore = weightedDifference(
            simRise, baseSimilarity,
            visualRise, baseVisual,
            planeRise, basePlane,
            ultraRise, baseUltra
        )

        val strongest = records.maxByOrNull {
            it.preFlyAwayMaxSimilarity * 0.35 +
            it.preFlyAwayMaxVisualChange * 0.25 +
            normalise(it.preFlyAwayMaxPlaneMotion) * 0.15 +
            it.preFlyAwayMaxUltraWatch * 0.25
        }?.round ?: 0

        return BehaviourAnalysis(
            records.size,
            baseSimilarity,
            preSimilarity,
            preVisual,
            prePlane,
            preUltra,
            simRise,
            visualRise,
            planeRise,
            ultraRise,
            endingScore,
            strongest
        )
    }

    private fun weightedDifference(
        sim: Double, simBase: Double,
        visual: Double, visualBase: Double,
        plane: Double, planeBase: Double,
        ultra: Double, ultraBase: Double
    ): Double {
        fun relative(delta: Double, base: Double): Double =
            if (base <= 0.0001) min(1.0, abs(delta)) else min(1.0, abs(delta) / max(base, 1.0))

        val score =
            relative(sim, simBase) * 0.30 +
            relative(visual, visualBase) * 0.25 +
            relative(plane, planeBase) * 0.15 +
            relative(ultra, ultraBase) * 0.30
        return score * 100.0
    }

    private fun normalise(value: Double): Double = min(100.0, value) / 100.0
}
