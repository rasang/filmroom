package dev.filmroom
import android.app.Application
import dev.filmroom.update.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,manifest=Config.NONE)
class AutoUpdateHostTest {
 @org.junit.Before fun resetProcessFixture(){
  UpdateChecks::class.java.getDeclaredField("startupConsidered").apply{isAccessible=true}.setBoolean(null,false)
  for(name in listOf("startupTask","running","delivered"))UpdateChecks::class.java.getDeclaredField(name).apply{isAccessible=true}.set(null,null)
  for(name in listOf("update_auto_cooldown","update_manual_success"))RuntimeEnvironment.getApplication().getSharedPreferences(name,0).edit().clear().commit()
 }

 private fun prefs()=RuntimeEnvironment.getApplication().getSharedPreferences("test",0).also{it.edit().clear().commit()}
 @Test fun twelveHoursAndRestart(){val p=prefs();assertTrue(UpdateCooldown(p).claim(100000,1000,1));assertFalse(UpdateCooldown(p).claim(100001,1001,1));assertFalse(UpdateCooldown(p).claim(900000000,1002,1));assertTrue(UpdateCooldown(p).claim(0,1000+UpdateCooldown.INTERVAL,1))}
 @Test fun rebootAndClockRollback(){val p=prefs();val c=UpdateCooldown(p);assertTrue(c.claim(100000,500,1));assertFalse(UpdateCooldown(p).claim(99000,20,2));assertFalse(c.claim(100000,21,2));assertTrue(c.claim(100000+UpdateCooldown.INTERVAL,20+UpdateCooldown.INTERVAL,2))}
 @Test fun automaticFailureCountsAndManualBypassesAndCoalesces()=runBlocking {
  val app=RuntimeEnvironment.getApplication();val started=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val first=UpdateChecks.request(app,true){started.countDown();release.await();throw java.io.IOException("offline")}!!
  assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue(app.getSharedPreferences("update_auto_cooldown",0).contains("wall"))
  assertSame(first,UpdateChecks.request(app,true){error("second auto")})
  assertSame(first,UpdateChecks.request(app,false){error("duplicate request")});release.countDown()
  try{first.await();fail()}catch(_:java.io.IOException){}
  val manifest=UpdateManifest(999,"test","",listOf(UpdateManifest.Download("a","https://example.com/a")))
  val manual=UpdateChecks.request(app,false){manifest}!!;assertSame(manifest,manual.await());assertTrue(UpdateChecks.claimResult(manifest));assertFalse(UpdateChecks.claimResult(manifest))
 }
 @Test fun coldStartPopupClosesWithHost(){
  val manifest=UpdateManifest(999,"test","- note",listOf(UpdateManifest.Download("a","https://example.com/a")))
  val host=org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
  val ui=UpdateSettingsUi(host.get(),endpoint="https://example.com/update.json",check={manifest})
  ui.checkOnColdStart()
  repeat(40){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10)}
  val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing)
  ui.close();host.pause().stop().destroy();assertFalse(dialog.isShowing)
 }

 @Test fun automaticOfflineIsSilent(){
  org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().use{host->
   val ui=UpdateSettingsUi(host.get(),endpoint="https://example.com/update.json",check={throw java.io.IOException("offline")})
   ui.checkOnColdStart();repeat(30){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10)}
   assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast());ui.close()
  }
 }

 @Test fun manualSuccessReusedAcrossProcessButFailureRetries()=runBlocking {
  val app=RuntimeEnvironment.getApplication();var calls=0
  val m=UpdateManifest(1,"old","",listOf(UpdateManifest.Download("a","https://example.com/a")))
  repeat(2){try{UpdateChecks.request(app,false){calls++;throw java.io.IOException()}!!.await();fail()}catch(_:java.io.IOException){}}
  assertEquals(2,calls)
  assertEquals(1L,UpdateChecks.request(app,false){calls++;m}!!.await().versionCode)
  UpdateChecks::class.java.getDeclaredField("running").apply{isAccessible=true}.set(null,null)
  assertEquals(1L,UpdateChecks.request(app,false){calls++;error("cached success")}!!.await().versionCode)
  assertEquals(3,calls)
 }
 @Test fun manualCacheClockAndExpiry(){
  val p=prefs();val cache=ManualUpdateCache(p);val m=UpdateManifest(999,"new","",listOf(UpdateManifest.Download("a","https://example.com/a")))
  cache.write(m,1000000,100,1)
  assertNotNull(ManualUpdateCache(p).read(999999999,101,1))
  assertNull(cache.read(1000001,100+ManualUpdateCache.INTERVAL,1))
  assertNotNull(cache.read(500000,1,2))
  assertNotNull(ManualUpdateCache(p).read(500001,2,2))
  assertNull(cache.read(500000+ManualUpdateCache.INTERVAL,1+ManualUpdateCache.INTERVAL,2))
 }
 @Test fun recreatedOwnerReceivesPendingStartupWithoutRefetch(){
  val started=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);var calls=0
  val m=UpdateManifest(999,"new","",listOf(UpdateManifest.Download("a","https://example.com/a")))
  val old=org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
  val ui=UpdateSettingsUi(old.get(),endpoint="https://example.com/update.json",check={calls++;started.countDown();release.await();m})
  ui.checkOnColdStart();assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));ui.close();old.close()
  org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().use{fresh->
   val next=UpdateSettingsUi(fresh.get(),endpoint="https://example.com/update.json",check={error("must join")});next.checkOnColdStart();release.countDown()
   repeat(40){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10)}
   val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing);assertEquals(1,calls);next.close()
  }
 }

}
