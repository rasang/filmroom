package dev.filmroom.effects

import kotlin.math.*

enum class EffectKind { LIGHT_TABLE, OPTICAL_LEAK }

/** Millisecond-driven state shared by on-screen playback and offline video export. */
object EffectTimeline {
    const val LIGHT_HOLD_MS = 1000L
    const val OPTICAL_HOLD_MS = 1200L
    const val OPTICAL_SLOW_HOLD_MS = 500L
    // Slow cover/settle remains 7 1/3 times normal; leak itself always keeps its 610 ms.
    fun opticalHoldMs(slow: Boolean) = if (slow) OPTICAL_SLOW_HOLD_MS else OPTICAL_HOLD_MS
    fun opticalTransitionMs(slow: Boolean) = if (slow) 1650L else 225L
    data class State(val gain:Float=1f,val lamp:Float=0f,val bloom:Float=0f,val cool:Float=0f,
                     val spill:Float=0f,val cover:Float=0f,val settling:Float=0f,
                     val leak:Float=0f,val leakDrift:Float=0f,val showResult:Boolean=false)
    @Suppress("UNUSED_PARAMETER")
    fun durationMs(kind:EffectKind,slow:Boolean=false):Long = 3000L
    fun at(kind:EffectKind,timeMs:Long,slow:Boolean=false):State {
        val t=timeMs.coerceIn(0L,durationMs(kind,slow)).toDouble()
        if(kind==EffectKind.LIGHT_TABLE) {
            // Move 610 ms of the final hold to the dark opening, preserving transition speed.
            val age=(t-LIGHT_HOLD_MS)*(5.0/3.0)
            if(age<0) return State(gain=.24f)
            if(age<35) { val s=(age/35).toFloat();return State(.24f+3.76f*s,s,.44f*s,.06f*s,s) }
            if(age<330) return State(4f,1f,.44f,.06f,1f)
            val q=((age-330)/2000).coerceIn(0.0,1.0)
            val fall=(exp(-4.7*q)-exp(-4.7))/(1-exp(-4.7))
            val cool=.22*sin(PI*min(1.0,q*1.35))*exp(-q*1.2)
            return State((1+3*fall).toFloat(),1f,(.44*fall.pow(1.65)).toFloat(),max(0.0,cool).toFloat(),(.32+.68*fall).toFloat())
        }
        val age=max(0.0,t-opticalHoldMs(slow))
        val factor=opticalTransitionMs(slow)/225.0
        val coverAge=age/factor
        val u=(coverAge/135).coerceIn(0.0,1.0)
        val leakAge=age-225*factor
        val power=if(leakAge<0||leakAge>=610) 0.0 else if(leakAge<=150) sin(leakAge/150*PI/2).pow(1.7) else max(0.0,1-(leakAge-150)/460).pow(1.65)
        return State(cover=(u*u*(3-2*u)).toFloat(),settling=if(coverAge>135 && coverAge<225) exp(-(coverAge-135)/32).toFloat() else 0f,
            leak=power.toFloat(),leakDrift=(leakAge/610).coerceIn(0.0,1.0).toFloat(),showResult=leakAge>=150)
    }
}
