package dev.filmroom
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Recovered after executor replacement. Re-run with the new toolchain; old passes do not certify this copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeHeroHostTest {
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @Test fun transparentLosslessAssetHasNoBakedBackground(){
  val app=RuntimeEnvironment.getApplication();val b=BitmapFactory.decodeResource(app.resources,R.drawable.welcome_cplift)
  assertTrue(b.hasAlpha());assertEquals(688,b.width);assertEquals(688,b.height)
  for(i in 0 until b.width){assertEquals(0,Color.alpha(b.getPixel(i,0)));assertEquals(0,Color.alpha(b.getPixel(i,b.height-1)));assertEquals(0,Color.alpha(b.getPixel(0,i)));assertEquals(0,Color.alpha(b.getPixel(b.width-1,i)))}
  var solid=0;var soft=0
  for(y in 0 until b.height)for(x in 0 until b.width){val a=Color.alpha(b.getPixel(x,y));if(a==255)solid++;if(a in 1..254)soft++}
  assertTrue(solid>50000);assertTrue("Antialiased silhouette retained",soft>100);b.recycle()
 }
 @Test(timeout=120000) fun welcomeHeroPreservesSizesInAllLanguagesAndSystemThemes(){
  resetAndroidViewModelFactoryForHostTest();val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
  val anim=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true};anim.invoke(null,0f)
  val out=File(System.getenv("FILMROOM_HERO_QA_DIR")?:"build/cplift-host-renders").apply{mkdirs()}
  try{for(tag in L10n.supported)for((w,h) in listOf(393 to 852,780 to 354))for(mode in listOf("night","notnight")){
   RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-$mode-mdpi");RuntimeEnvironment.setFontScale(1f);L10n.choose(app,tag)
   Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
    val a=c.get();val vm=androidx.lifecycle.ViewModelProvider(a)[EditorViewModel::class.java];val end=System.nanoTime()+5_000_000_000L
    while(!vm.state.value.startupReady && System.nanoTime()<end){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5)};assertTrue(vm.state.value.startupReady)
    val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0);root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
    val scroll=MainActivity::class.java.getDeclaredField("welcomeScroll").apply{isAccessible=true}.get(a) as ScrollView
    val hero=all(scroll).filterIsInstance<ImageView>().single();assertTrue(hero.drawable is BitmapDrawable);val expected=BitmapFactory.decodeResource(a.resources,R.drawable.welcome_cplift);assertTrue("Welcome must use approved bitmap",(hero.drawable as BitmapDrawable).bitmap.sameAs(expected));expected.recycle()
    assertEquals(if(w>h)40 else 76,hero.width);assertEquals(hero.width,hero.height);assertEquals(View.VISIBLE,scroll.visibility)
    assertEquals("Launcher must remain unchanged",R.mipmap.ic_launcher,app.applicationInfo.icon)
    val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(image));File(out,"$tag-${w}x$h-$mode.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    val detail=Bitmap.createBitmap(hero.width*4,hero.height*4,Bitmap.Config.ARGB_8888);val canvas=Canvas(detail);canvas.drawColor(Color.rgb(17,20,19));canvas.scale(4f,4f);hero.draw(canvas);File(out,"hero-$tag-${w}-$mode.png").outputStream().use{detail.compress(Bitmap.CompressFormat.PNG,100,it)};detail.recycle()
   }
  }}finally{L10n.choose(app,"zh");anim.invoke(null,1f)}
 }
}
