package dev.filmroom

import android.app.AlertDialog
import android.app.Application
import android.view.ViewGroup
import android.view.View
import androidx.activity.ComponentActivity
import dev.filmroom.update.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,manifest=Config.NONE)
class UpdateClientHostTest {
 private val url="https://updates.example.com/latest.json"
 private fun body(code:String="100")="""{"versionCode":$code,"versionName":"0.1-beta10","releaseNotes":"- one\n* two\n+ three","downloads":[{"name":"A","url":"https://a.example.com/app.apk"},{"name":"B","url":"https://b.example.com/app.apk"}]}"""
 private fun rejects(block:()->Unit){try{block();fail("Expected rejection")}catch(e:IllegalArgumentException){}catch(e:org.json.JSONException){}}
 @Test fun strictManifestAndComparison(){val m=UpdateManifest.parse(body());assertTrue(m.isNewerThan(99));assertFalse(m.isNewerThan(100));assertFalse(m.isNewerThan(101));for(c in listOf("1.5","\"100\"","0","-1","2147483648","true"))rejects{UpdateManifest.parse(body(c))};rejects{UpdateManifest.parse(body().replace("https://a","http://a"))};rejects{UpdateManifest.parse(body().replace("\"downloads\":[","\"downloads\":[],\"other\":["))}}
 @Test fun unsafeUrlsRejected(){for(s in listOf("http://example.com/a","https://u:p@example.com/a","https://127.0.0.1/a","https://localhost/a","https://a.local/a","https://example.com:8080/a","https://example.com/#x"," https://example.com/a","file:///tmp/a","https://example.com/\\evil"))rejects{SafeUpdateUrl.requireHttps(s)}}
 @Test fun notesOnlyRenderListMarkers(){assertEquals("• one\n• two\n• three",renderReleaseNotes(UpdateManifest.parse(body()).releaseNotes));assertEquals("heading\n  • item\n\n<b>x</b>\n[link](https://x)",renderReleaseNotes("heading\n  - item\n\n<b>x</b>\n[link](https://x)"))}
 @Test fun onlineRevalidationAndNoStaleFallback(){var entry:UpdateCache.Entry?=null;val cache=object:UpdateCache{override fun read()=entry;override fun write(e:UpdateCache.Entry){entry=e}};var calls=0;val repo=UpdateRepository(cache,UpdateTransport{_,tag->calls++;if(calls==1){assertNull(tag);UpdateTransport.Response(200,body(),"abc")}else{assertEquals("abc",tag);UpdateTransport.Response(304,null,null)}});assertEquals(100L,repo.check(url).versionCode);assertEquals(100L,repo.check(url).versionCode);assertEquals(2,calls);try{UpdateRepository(cache,UpdateTransport{_,_->throw java.io.IOException()}).check(url);fail()}catch(_:java.io.IOException){};assertNotNull(entry)}
 @Test fun failedLinkAndRepeatedLinksKeepDialogOpen(){Robolectric.buildActivity(ComponentActivity::class.java).setup().use{host->val a=host.get();a.setTheme(android.R.style.Theme_Material_NoActionBar);val clicked=mutableListOf<String>();val ui=UpdateSettingsUi(a,openLink={clicked.add(it);if(clicked.size==1)throw IllegalStateException()});ui.showUpdate(UpdateManifest.parse(body()));val dialog=ShadowAlertDialog.getLatestAlertDialog();fun find(v:View,tag:String):View?{if(v.tag==tag)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it};return null};val decor=dialog.window!!.decorView;find(decor,"update-download:0")!!.performClick();assertTrue(dialog.isShowing);find(decor,"update-download:1")!!.performClick();find(decor,"update-download:0")!!.performClick();assertTrue(dialog.isShowing);assertEquals(3,clicked.size);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertFalse(dialog.isShowing);ui.close()}}
 @Test fun boundedPayloadAndLinks(){rejects{UpdateManifest.parse(body().replace("0.1-beta10","x".repeat(81)))};rejects{UpdateManifest.parse(" ".repeat(UpdateManifest.MAX_BYTES+1))};rejects{UpdateManifest.parse(body().replace("https://b.example.com/app.apk","https://a.example.com/app.apk"))};assertEquals("https://wwbkn.lanzouc.com/i1n8q4ey9k6d",SafeUpdateUrl.requireHttps("https://wwbkn.lanzouc.com/i1n8q4ey9k6d"));assertTrue(SafeUpdateUrl.requireHttps("https://pan.baidu.com/s/1qCuWVmdU1bgtMCPp5hfyyw?pwd=2k8x").contains("?pwd="))}
 @Test fun invalidCachedBodyIsNotUsedFor304(){val cache=object:UpdateCache{override fun read()=UpdateCache.Entry(url,"broken","abc");override fun write(entry:UpdateCache.Entry){fail()}};try{UpdateRepository(cache,UpdateTransport{_,etag->assertNull(etag);UpdateTransport.Response(304,null,null)}).check(url);fail()}catch(_:java.io.IOException){}}
 @Test fun unconfiguredNeverCallsNetwork(){Robolectric.buildActivity(ComponentActivity::class.java).setup().use{host->val a=host.get();var calls=0;val ui=UpdateSettingsUi(a,endpoint="",check={calls++;UpdateManifest.parse(body())});val layout=android.widget.LinearLayout(a);ui.addTo(layout);layout.findViewWithTag<View>("check-updates").performClick();assertEquals(0,calls);ui.close();ui.showUpdate(UpdateManifest.parse(body()));assertNull(ShadowAlertDialog.getLatestAlertDialog())}}

}
