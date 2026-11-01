package dev.filmroom

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric replaces Application between test methods, unlike a real Android process.
 * AndroidX 2.8.7's process singleton otherwise retains the previous method's Application.
 * Do not call this from production or between Activity recreations within one test.
 */
fun resetAndroidViewModelFactoryForHostTest() {
    val type=ViewModelProvider.AndroidViewModelFactory::class.java
    val singleton=type.getDeclaredField("_instance").apply {isAccessible=true}
    val previous=singleton.get(null)
    val owner=previous?.let {type.getDeclaredField("application").apply {isAccessible=true}.get(it) as? Application}
    val current=RuntimeEnvironment.getApplication()
    fun describe(app:Application?)=app?.let {
        "${it.javaClass.name}@${System.identityHashCode(it).toString(16)} data=${runCatching{it.applicationInfo.dataDir}.getOrNull()}"
    } ?: "none"
    println("HOST_FACTORY before=${describe(owner)} current=${describe(current)} stale=${owner!=null && owner!==current}")
    singleton.set(null,null)
    check(singleton.get(null)==null)
}
