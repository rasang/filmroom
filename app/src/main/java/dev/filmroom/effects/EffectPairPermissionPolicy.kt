package dev.filmroom.effects

/** Request once in this screen session, only after discovering an inaccessible paired Live input. */
internal object EffectPairPermissionPolicy {
    fun shouldReloadAfterAccessChange(previous:Int?, current:Int, knownPair:Boolean, hasVideo:Boolean):Boolean =
        previous!=null && (previous!=current || current==1) && current>0 && knownPair && !hasVideo

    fun shouldRequest(viewfinder:Boolean, knownPair:Boolean, hasVideo:Boolean,
        fullAccess:Boolean, attempted:Boolean, pending:Boolean):Boolean =
        viewfinder && knownPair && !hasVideo && !fullAccess && !attempted && !pending
}
