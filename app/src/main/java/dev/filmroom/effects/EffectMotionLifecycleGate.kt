package dev.filmroom.effects

internal class EffectMotionLifecycleGate {
    private val lock=Any()
    @Volatile var paused=false
        private set
    fun pause()=synchronized(lock){paused=true}
    fun resume()=synchronized(lock){paused=false}
    fun publish(assign:()->Unit):Boolean=synchronized(lock){if(paused)false else {assign();true}}
    fun <T> take(detach:()->T):T=synchronized(lock){detach()}
}
