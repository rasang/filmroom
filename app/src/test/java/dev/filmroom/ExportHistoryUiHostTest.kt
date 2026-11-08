package dev.filmroom

import android.app.Application
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.*
import dev.filmroom.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportHistoryUiHostTest {
 private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
 private fun language(tag:String="zh"){dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),tag)}
 @Test fun choiceDefaultsRecordsUncheckedHintOnlyAfterRememberAndCancelNoCallback(){language();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  var called=false;val d=showHistoryChoice(c.get(),ExportHistoryAction.SINGLE,true){_,_->called=true}
  val all=all(d.window!!.decorView);val radios=all.filterIsInstance<RadioButton>();assertTrue(radios[0].isChecked);assertFalse(radios[1].isChecked)
  val box=all.filterIsInstance<CheckBox>().single();val hint=all.single{it.tag=="history-remember-hint"};assertFalse(box.isChecked);assertEquals(View.GONE,hint.visibility);box.isChecked=true;assertEquals(View.VISIBLE,hint.visibility);box.isChecked=false;assertEquals(View.GONE,hint.visibility)
  d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(called)
 }}
 @Test fun rememberedScopeGetsSimpleConfirmationForBothActionsAndAllLanguages(){for(lang in listOf("zh","en","ja","ko"))for(action in ExportHistoryAction.entries)for(mode in listOf(ExportHistoryMode.RECORDS_ONLY,ExportHistoryMode.RECORDS_AND_PHOTOS)){language(lang);Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  var result:Pair<ExportHistoryMode,Boolean>?=null
  val d=showHistoryPrompt(c.get(),action,false,2,mode){m,r->result=m to r}
  val views=all(d.window!!.decorView)
  assertTrue(views.filterIsInstance<RadioButton>().isEmpty());assertTrue(views.filterIsInstance<CheckBox>().isEmpty());assertNull(result)
  val key=if(mode==ExportHistoryMode.RECORDS_AND_PHOTOS)HistoryText.CONFIRM_PHOTOS else HistoryText.CONFIRM_RECORDS
  assertTrue(views.filterIsInstance<TextView>().any{it.text.contains(key.text(c.get(),2))})
  d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(mode to false,result)
 }}}
 @Test fun rememberedCancelAndDismissNeverConfirm(){language();for(action in ExportHistoryAction.entries){Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  var calls=0
  val d=showHistoryPrompt(c.get(),action,true,1,ExportHistoryMode.RECORDS_AND_PHOTOS){_,_->calls++}
  d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(0,calls)
  val dismissed=showHistoryPrompt(c.get(),action,true,1,ExportHistoryMode.RECORDS_ONLY){_,_->calls++};dismissed.cancel();assertEquals(0,calls)
 }}}
 @Test fun askUsesScopeChooserAndConfirmsOnceWithoutSecondDialog(){language();for(action in ExportHistoryAction.entries){Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  var calls=0;var result:Pair<ExportHistoryMode,Boolean>?=null
  val d=showHistoryPrompt(c.get(),action,false,1,ExportHistoryMode.ASK){m,r->calls++;result=m to r}
  val views=all(d.window!!.decorView);assertEquals(2,views.filterIsInstance<RadioButton>().size)
  views.filterIsInstance<RadioButton>()[1].performClick();views.filterIsInstance<CheckBox>().single().isChecked=true
  d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
  assertEquals(1,calls);assertEquals(ExportHistoryMode.RECORDS_AND_PHOTOS to true,result);assertSame(d,org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
 }}}
 @Test fun clearAndSingleUseDistinctTitlesCopyAndConfirmationInAllLanguages(){for(lang in listOf("zh","en","ja","ko")){language(lang);Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val single=showHistoryChoice(c.get(),ExportHistoryAction.SINGLE,false){_,_->};val singleTitle=shadowOf(single).title.toString();val singleConfirm=single.getButton(AlertDialog.BUTTON_POSITIVE).text.toString();single.dismiss()
  val clear=showHistoryChoice(c.get(),ExportHistoryAction.CLEAR,false,3){_,_->};assertNotEquals(singleTitle,shadowOf(clear).title.toString());assertNotEquals(singleConfirm,clear.getButton(AlertDialog.BUTTON_POSITIVE).text.toString());assertTrue(all(clear.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString()==HistoryText.PHOTO_MANY.text(c.get())});clear.dismiss()
 }}}
 @Test fun explicitPhotoChoiceAndRememberAreReturnedOnlyOnConfirm(){language();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  var result:Pair<ExportHistoryMode,Boolean>?=null;val d=showHistoryChoice(c.get(),ExportHistoryAction.CLEAR,true){mode,remember->result=mode to remember};val views=all(d.window!!.decorView)
  views.filterIsInstance<RadioButton>()[1].performClick();views.filterIsInstance<CheckBox>().single().isChecked=true;assertNull(result);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(ExportHistoryMode.RECORDS_AND_PHOTOS to true,result)
 }}
 @Test fun settingsExposeTwoIndependentKeysAndCancelDoesNotSave(){language();val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("export-history-preferences",0).edit().clear().commit();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val views=all(c.get().window.decorView);val single=views.single{it.tag=="history-setting:single-delete-mode"};val clear=views.single{it.tag=="history-setting:clear-history-mode"};assertNotSame(single,clear)
  single.performClick();val d=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();d.listView.performItemClick(d.listView.getChildAt(2),2,2);d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(ExportHistoryMode.ASK,ExportHistoryPreferences(context).get(ExportHistoryAction.SINGLE))
 }}
 @Test fun fourLanguagesHaveExplicitMissingSafetyCopyAndIndependentSettingTitles(){val context=RuntimeEnvironment.getApplication();for(lang in listOf("zh","en","ja","ko")){language(lang);assertTrue(HistoryText.MISSING.text(context).isNotBlank());assertNotEquals(HistoryText.MISSING.text(context),HistoryText.DENIED.text(context));assertNotEquals(HistoryText.SINGLE_SETTING.text(context),HistoryText.CLEAR_SETTING.text(context));HistoryText.entries.forEach{assertTrue(it.text(context).isNotBlank())}};language();assertEquals("未找到导出照片，可能已移动或删除。",HistoryText.MISSING.text(context))}
 @Test fun rememberedRestoreWithoutOsCallbackDoesNotDisableFutureActions(){language();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->val controller=ExportHistoryRemovalController(c.get()){};controller.restore(android.os.Bundle().apply{putString("history-operation",UUID.randomUUID().toString())});assertFalse(controller.busy);controller.close(false)}}
 @Test fun delayedCallbackFromOldOperationCannotBindNewOperation(){language();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val flow=ExportHistoryRemovalController(c.get()){};val old=UUID.randomUUID().toString();val fresh=UUID.randomUUID().toString();flow.restore(android.os.Bundle().apply{putString("history-operation",old)})
  val registry=c.get().activityResultRegistry;val codes=androidx.activity.result.ActivityResultRegistry::class.java.getDeclaredField("keyToRc").apply{isAccessible=true}.get(registry) as Map<*,*>;val oldCode=codes["export-history-consent-$old"] as Int
  @Suppress("UNCHECKED_CAST") val launched=androidx.activity.result.ActivityResultRegistry::class.java.getDeclaredField("launchedKeys").apply{isAccessible=true}.get(registry) as MutableList<String>;launched.add("export-history-consent-$old")
  flow.restore(android.os.Bundle().apply{putString("history-operation",fresh)});registry.dispatchResult(oldCode,android.app.Activity.RESULT_OK,android.content.Intent());shadowOf(android.os.Looper.getMainLooper()).idle()
  assertNull(ExportHistoryRemovalController::class.java.getDeclaredField("consentResult").apply{isAccessible=true}.get(flow));assertEquals(fresh,ExportHistoryRemovalController::class.java.getDeclaredField("restoredId").apply{isAccessible=true}.get(flow));flow.close(false)
 }}
 @Test fun cardHasDetailsIconBeforeDeleteAndNoAnimationForFailedRemoval(){language();Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
  val a=c.get();shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
  val job=ExportJob(UUID.randomUUID().toString(),1,EditorState(),20,20,ExportFormat.JPEG,95,ExportJobState.COMPLETED)
  val render=ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true};render.invoke(a,listOf(job))
  val views=all(a.window.decorView);val detail=views.filterIsInstance<Button>().single{it.text.toString()=="详情"};val delete=views.filterIsInstance<Button>().single{it.tag=="history-delete:${job.id}"};assertNotNull(detail.compoundDrawablesRelative[0]);assertSame(detail.parent,delete.parent);assertTrue((detail.parent as ViewGroup).indexOfChild(detail)<(delete.parent as ViewGroup).indexOfChild(delete))
  val row=views.single{it.tag=="export-job:${job.id}"};render.invoke(a,listOf(job));assertSame(row,all(a.window.decorView).single{it.tag=="export-job:${job.id}"});assertEquals(0f,row.translationX)
 }}
 @Test fun removedStableIdSlidesThenCollapsesAndStopFinishesWithoutRemovingOtherCards(){language()
  // This test persists synthetic rows. Do not leak its process singleton into later Activity fixtures.
  val singleton=ExportQueue::class.java.getDeclaredField("instance").apply{isAccessible=true}
  val previous=singleton.get(null);singleton.set(null,null)
  try{Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
  val a=c.get();shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));val jobs=(1..2).map{ExportJob(UUID.randomUUID().toString(),it.toLong(),EditorState(),20,20,ExportFormat.JPEG,95,ExportJobState.COMPLETED)}
  var queue:ExportQueue?=null
  for(attempt in 0 until 300){shadowOf(android.os.Looper.getMainLooper()).idle();queue=ExportQueueActivity::class.java.getDeclaredField("queue").apply{isAccessible=true}.get(a) as? ExportQueue;if(queue!=null)break;Thread.sleep(5)}
  val q=checkNotNull(queue);jobs.forEach(q.store::update);shadowOf(android.os.Looper.getMainLooper()).idle()
  val render=ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true};render.invoke(a,jobs)
  val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0);root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)
  val controller=ExportQueueActivity::class.java.getDeclaredField("historyFlow").apply{isAccessible=true}.get(a) as ExportHistoryRemovalController
  ExportHistoryRemovalController::class.java.getDeclaredField("singleRemovalId").apply{isAccessible=true}.set(controller,jobs[1].id)
  val remaining=all(root).single{it.tag=="export-job:${jobs[0].id}"}
  kotlinx.coroutines.runBlocking{val op=q.beginHistoryRemoval(q.historySnapshot(setOf(jobs[1].id)),false);assertTrue(q.removeHistoryRecord(op,op.targets.single()));q.store.releaseHistory(op)}
  render.invoke(a,q.jobs.value)
  val leaving=ExportQueueActivity::class.java.getDeclaredField("leavingCards").apply{isAccessible=true}.get(a) as Map<*,*>;assertTrue("Successful single removal must enter animation",leaving.containsKey(jobs[1].id))
  val departed=all(root).single{it.tag=="export-job:${jobs[1].id}"};shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(90));assertTrue("Card must slide left",departed.translationX<0f)
  val newest=jobs[0].copy(id=UUID.randomUUID().toString(),createdAt=3);q.store.update(newest);render.invoke(a,q.jobs.value);assertSame(remaining,all(root).single{it.tag=="export-job:${jobs[0].id}"})
  c.pause().stop();assertFalse(all(root).any{it.tag=="export-job:${jobs[1].id}"});assertSame(remaining,all(root).single{it.tag=="export-job:${jobs[0].id}"});assertTrue(all(root).any{it.tag=="export-job:${newest.id}"})
 }}finally{singleton.set(null,previous)}
 }
}
