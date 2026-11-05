package dev.filmroom.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Keeps ambiguous GPU ownership outside the normal work queue, without fresh admission. */
internal class GpuResourceQuarantine {
    val retention=NativeWorkRetention()
    @Volatile var retained=false
        private set
    private var roots:List<Any> = emptyList()
    fun retain(router:GpuExportRouter,owned:List<Any>,cleanup:()->Unit) = retainAfterProof(owned,{safe->router.whenSafeToRelease(safe)},cleanup)
    fun retain(frame:GpuScienceFrameDelegate,owned:List<Any>,cleanup:()->Unit) = retainAfterProof(owned,{safe->frame.whenSafeToRelease(safe)},cleanup)
    internal fun retainAfterProof(owned:List<Any>,register:(()->Unit)->Unit,cleanup:()->Unit) {
        check(!retained)
        retained=true;roots=owned;owners[this]=this
        retention.retainUntil { release ->
            register {
                // Binder callbacks never perform filesystem or native cleanup on Main.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        cleanup()
                        roots=emptyList()
                        release()
                        owners.remove(this@GpuResourceQuarantine)
                    } catch(_:Exception) {
                        // Failed ownership/cleanup checks keep roots and prepaid bytes.
                    } catch(_:LinkageError) {
                        // No successful cleanup proof: preserve the registered owner.
                    }
                }
            }
        }
    }
    private companion object {val owners=ConcurrentHashMap<GpuResourceQuarantine,GpuResourceQuarantine>()}
}
