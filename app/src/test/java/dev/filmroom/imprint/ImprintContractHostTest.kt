package dev.filmroom.imprint

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.*
import dev.filmroom.border.*
import dev.filmroom.crop.*
import dev.filmroom.io.*
import dev.filmroom.model.*
import dev.filmroom.export.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintContractHostTest {
    private fun idleUi()=org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList())
    private fun click(dialog:AlertDialog,which:Int) {
        idleUi();assertTrue(dialog.getButton(which).performClick());idleUi()
    }
    private fun datePicker(dialog:AlertDialog):DatePickerDialog {
        assertTrue(dialog.window!!.decorView.findViewWithTag<Button>("imprint-date-value").performClick());idleUi()
        return (ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog).also{assertTrue(it.isShowing)}
    }
    private fun chooseDate(dialog:AlertDialog,year:Int,month:Int,day:Int) {
        val picker=datePicker(dialog);picker.updateDate(year,month-1,day)
        click(picker,AlertDialog.BUTTON_POSITIVE);assertFalse(picker.isShowing)
    }
    private fun assertNoTextEntry(dialog:AlertDialog) {
        val root=dialog.window!!.decorView
        assertTrue("Date entry uses a picker button, not an editable text field",views(root).none{it is EditText})
        for(tag in listOf("imprint-date-value")) {
            val button=root.findViewWithTag<Button>(tag)
            assertNotNull(tag,button);assertFalse(button.onCheckIsTextEditor());assertTrue(button.isEnabled)
        }
        assertNull(root.findViewWithTag<View>("imprint-time-value"))
        val styles=root.findViewWithTag<RadioGroup>("imprint-style")
        assertEquals(4,styles.childCount)
        repeat(styles.childCount){assertTrue(styles.getChildAt(it) is RadioButton)}
    }
    private fun assertIncompleteCannotApply(dialog:AlertDialog) {
        click(dialog,AlertDialog.BUTTON_POSITIVE);assertTrue(dialog.isShowing)
        val error=dialog.window!!.decorView.findViewWithTag<TextView>("imprint-date-error")
        assertEquals(View.VISIBLE,error.visibility);assertTrue(error.text.isNotBlank())
    }
    private fun meta()=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2025:04:28 00:01:02","OffsetTimeOriginal" to "+14:00","ExposureProgram" to "3","MeteringMode" to "5","ExposureTime" to "1/250","FNumber" to "56/10","ExposureBiasValue" to "1/2","ExposureMode" to "2","PhotographicSensitivity" to "400","FocalLength" to "50/1"))
    private val setting=ImprintSettings("contax_t2",exposure=true)
    @Test fun sourceWallDateAndFourFormatsNeverUseDeviceZone() {
        val m=meta()
        assertEquals("'25 04 28",ImprintContent.from(m,setting).date)
        assertEquals("25 04.28",ImprintContent.from(m,setting.copy(dateStyle="yashica_t4")).date)
        assertEquals("28 4 25",ImprintContent.from(m,setting.copy(dateStyle="canon_data_back")).date)
        assertEquals("25年4月28日",ImprintContent.from(m,setting.copy(dateStyle="canon_luna")).date)
        assertEquals(ImprintContent.from(m,setting),ImprintContent.from(PhotoMetadata.fromJson(m.toJson()),setting))
    }
    @Test fun missingAndInvalidNeverInventDate() {
        for(m in listOf(PhotoMetadata.unavailable(),PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2025:02:30 00:00:00")))) {
            val c=ImprintContent.from(m,setting);assertNull(c.date);assertTrue(c.fields.isEmpty())
        }
    }
    @Test fun sourceFactsOnlyIncludeIsoAndFocalNeverModeAebOrAf() {
        val c=ImprintContent.from(meta(),setting)
        assertEquals(ImprintFieldKind.entries.filter{it!=ImprintFieldKind.METERING},c.fields.map{it.kind})
        assertEquals(listOf("ISO400","250","F5.6","+0.5","50mm"),c.fields.map{it.text})
        assertTrue(c.fields.none { it.kind==ImprintFieldKind.METERING })
        val ignored=PhotoMetadata.fromAttributes(mapOf("ExposureProgram" to "3","ExposureMode" to "2","FocalLengthIn35mmFilm" to "50"))
        assertTrue(ImprintContent.from(ignored,setting).fields.isEmpty())
        val partial=PhotoMetadata.fromAttributes(mapOf("FNumber" to "2/1","ExposureProgram" to "99","ExposureBiasValue" to "0/1"))
        assertEquals(listOf("F2","+0"),ImprintContent.from(partial,setting).fields.map{it.text})
    }
    @Test fun oldManualDraftsMigratePresentationOnlyAndNewPhotoHasNoCaptureFacts() {
        val legacy=setting.json().put("version",1).put("dateSource","manual").put("manualDate","1999-12-31")
        legacy.remove("datePlacement");legacy.remove("layoutVersion")
        val migrated=ImprintSettings.fromJson(legacy)
        assertEquals("photo",migrated.datePlacement)
        assertEquals("'25 04 28",ImprintContent.from(meta(),migrated).date)
        assertNull(ImprintContent.from(PhotoMetadata.unavailable(),migrated).date)
        assertEquals(migrated,migrated.forNewPhoto())
        assertFalse(migrated.json().has("manualDate"));assertFalse(migrated.json().has("dateSource"))
        assertEquals(3,migrated.json().getInt("version"))
    }
    @Test fun presentationPersistsButNeverEntersNativeParameters() {
        val s=EditorState(imprint=setting.copy(datePlacement="edge",corner="top_left",size=.7,glow=.29,manualDateTime="1999-12-31 01:02:03"))
        assertEquals(s,EditorState.fromJson(s.json()))
        assertEquals(EditorState().json(includePresentation=false),s.json(includePresentation=false))
        assertEquals(ImprintSettings(),EditorState.fromJson("{}").imprint)
        assertEquals(s.imprint,s.processingDefaults(s.paperStock,s.outputRoute).imprint)
        assertThrows(IllegalArgumentException::class.java){ImprintSettings(size=Double.NaN)}
        assertThrows(IllegalArgumentException::class.java){ImprintSettings.fromJson(setting.json().put("version",4))}
    }
    @Test fun datesRemainInsideCroppedPhotoAndAllCorners() {
        for((w,h) in listOf(320 to 180,180 to 320,64 to 64,1 to 1))for(corner in ImprintSettings.CORNERS) {
            val plan=ImprintPlan.create(setting.copy(corner=corner),meta(),w,h)
            assertTrue(plan.stamps.all{it.left>=0&&it.top>=0&&it.right<=w&&it.bottom<=h})
            if(w>100)assertEquals(1,plan.stamps.size)
        }
    }
    @Test fun packed135FieldsRotateAndUnsupportedEdgeDateFallsBackIntoPhoto() {
        for((w,h) in listOf(720 to 480,480 to 720)) {
            val b=BorderSnapshot.fixed135("kodak_portra_400","test",w,h)
            val p=ImprintPlan.create(setting,meta(),b.outputWidth,b.outputHeight,b)
            assertEquals(6,p.stamps.size)
            p.stamps.forEach{assertTrue(it.left>=0&&it.top>=0&&it.right<=p.width&&it.bottom<=p.height)}
            val noDate=ImprintPlan.create(setting.copy(datePlacement="edge"),meta(),b.outputWidth,b.outputHeight,b)
            assertEquals(6,noDate.stamps.size)
        }
        assertEquals(1,ImprintPlan.create(setting,meta(),720,480).stamps.size)
        assertEquals(1,ImprintPlan.create(setting.copy(datePlacement="edge"),meta(),720,480).stamps.size)
        assertFalse(ImprintPlan.exposureSupported(BorderSnapshot.authored("kodak_trix","test",720,480)))
    }
    private fun source(w:Int,h:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(50,80,100))}
    private fun writeLinear(bitmap:Bitmap,file:File) {
        val buffer=ByteBuffer.allocate(bitmap.width*bitmap.height*12).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val p=bitmap.getPixel(x,y);buffer.putFloat(LinearRgb.srgb8ToLinear[(p ushr 16)and 255]);buffer.putFloat(LinearRgb.srgb8ToLinear[(p ushr 8)and 255]);buffer.putFloat(LinearRgb.srgb8ToLinear[p and 255])}
        file.writeBytes(buffer.array())
    }
    @Test fun previewAndFloatFileUseIdenticalRows() {
        val photo=source(320,240);val dir=createTempDir();val input=File(dir,"in.rgb");val out=File(dir,"out.rgb");writeLinear(photo,input)
        val crop=CropSnapshot.create(320,240,settings=CropSettings(CropMode.SQUARE))
        val plan=ImprintPlan.create(setting,meta(),crop.outputWidth,crop.outputHeight)
        val bitmap=CropPreview.compose(photo,crop,plan)
        CropCompositor.composite(input,out,crop,plan)
        val floats=ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val rgb=(0xff shl 24)or(OutputColorSpace.SRGB.encode8(floats.get()) shl 16)or(OutputColorSpace.SRGB.encode8(floats.get()) shl 8)or OutputColorSpace.SRGB.encode8(floats.get());assertEquals(rgb,bitmap.getPixel(x,y))}
        assertEquals(0L,CropCompositor.additionalDiskBytes(CropSnapshot.create(320,240)))
        assertTrue(CropCompositor.additionalDiskBytes(CropSnapshot.create(320,240),true)>0)
        bitmap.recycle();photo.recycle();dir.deleteRecursively()
    }
    @Test fun borderPreviewAndFloatFileUseIdenticalRowsInBothOrientations() {
        for(portrait in listOf(false,true)) {
            val photo=source(if(portrait)240 else 320,if(portrait)320 else 240)
            val b=BorderSnapshot.authored("kodak_portra_400","test",photo.width,photo.height)
            val raster=object:BorderRaster {
                override val stock=b.stock
                override val version=b.assetVersion
                override val railRatio=.1
                override val endRatio=.02
                override val residentBytes=12L
                override val featherTotalAt3072=b.featherTotalAt3072
                override fun validate(snapshot:BorderSnapshot){assertEquals(b,snapshot)}
                override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray) {
                    rgb[0]=.15f;rgb[1]=.08f;rgb[2]=.03f
                }
            }
            val dir=createTempDir();val input=File(dir,"in.rgb");val out=File(dir,"out.rgb")
            writeLinear(photo,input);val original=input.readBytes()
            val metadata=meta();val frozen=metadata.toJson()
            val plan=ImprintPlan.create(setting,metadata,b.outputWidth,b.outputHeight,b)
            assertFalse(plan.isEmpty)
            val bitmap=BorderPreview.compose(photo,b,raster,plan)
            BorderCompositor.composite(input,out,b,raster,OutputColorSpace.SRGB,plan)
            val floats=ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {
                val rgb=(0xff shl 24)or(OutputColorSpace.SRGB.encode8(floats.get()) shl 16)or
                    (OutputColorSpace.SRGB.encode8(floats.get()) shl 8)or OutputColorSpace.SRGB.encode8(floats.get())
                assertEquals("border row $y column $x",rgb,bitmap.getPixel(x,y))
            }
            assertArrayEquals(original,input.readBytes());assertEquals(frozen,metadata.toJson())
            val cancelled=File(dir,"cancelled.rgb");var checks=0
            assertThrows(IllegalStateException::class.java) {
                BorderCompositor.composite(input,cancelled,b,raster,OutputColorSpace.SRGB,plan){if(checks++==10)error("cancel")}
            }
            assertFalse(cancelled.exists());assertFalse(File(dir,"cancelled.rgb.part").exists())
            assertArrayEquals(original,input.readBytes())
            bitmap.recycle();photo.recycle();dir.deleteRecursively()
        }
    }
    @Test fun cancellationCleansPartialOutputAndNeverChangesInput() {
        val photo=source(80,60);val dir=createTempDir();val input=File(dir,"in.rgb");writeLinear(photo,input);val original=input.readBytes();val out=File(dir,"out.rgb")
        val crop=CropSnapshot.create(80,60);val plan=ImprintPlan.create(setting,meta(),80,60)
        var rows=0
        assertThrows(IllegalStateException::class.java){CropCompositor.composite(input,out,crop,plan){if(rows++==10)error("cancel")}}
        assertFalse(out.exists());assertFalse(File(dir,"out.rgb.part").exists());assertArrayEquals(original,input.readBytes());photo.recycle();dir.deleteRecursively()
    }
    @Test fun queueFreezesMetadataSettingsAndRejectsDowngradeVersion() {
        val dir=createTempDir();val store=ExportJobStore(dir);val id=ExportJobStore.newId();val crop=CropSnapshot.create(320,240)
        val frozenSetting=setting.copy(manualDateTime="1999-12-31 01:02:03")
        val job=ExportJob(id,1,EditorState(imprint=frozenSetting),320,240,ExportFormat.PNG,95,crop=crop,photoMetadata=meta().toJson())
        store.update(job);val restored=checkNotNull(ExportJobStore(dir).get(id));assertEquals(frozenSetting,restored.parameters.imprint);assertEquals(job.photoMetadata,restored.photoMetadata)
        val state=File(File(dir,id),"manifest.json")
        assertTrue(state.isFile);assertEquals(7,JSONObject(state.readText()).getInt("version"))
        state.writeText(JSONObject(state.readText()).put("version",4).toString())
        val rejected=checkNotNull(ExportJobStore(dir).get(id))
        assertEquals(ExportJobState.FAILED,rejected.state);assertFalse(rejected.inputReady)
        dir.deleteRecursively()
    }
    @Test fun applyCancelBackAndRecreatedDraftPreserveOnlyPresentation() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity=controller.get()
        var result:ImprintSettings?=null
        val old=setting.copy(size=.7,glow=.29)
        val d=ImprintDialog.show(activity,EditorState(imprint=old),meta()){result=it}
        idleUi()
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(old,result)
        result=null
        val cancelled=ImprintDialog.show(activity,EditorState(imprint=old),meta()){result=it}
        idleUi()
        cancelled.window!!.decorView.findViewWithTag<SeekBar>("imprint-size").progress=88
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idleUi();assertNull(result)
        val reopened=ImprintDialog.show(activity,EditorState(imprint=old),meta()){result=it}
        idleUi()
        assertEquals(10,reopened.window!!.decorView.findViewWithTag<SeekBar>("imprint-size").progress)
        assertNoTextEntry(reopened)
        assertNotNull(reopened.window!!.decorView.findViewWithTag<android.view.View>("imprint-restore-capture"))
        reopened.window!!.decorView.findViewWithTag<SeekBar>("imprint-size").progress=88
        reopened.window!!.decorView.findViewWithTag<Switch>("imprint-placement").isChecked=true
        val draft=ImprintDialog.capture(reopened);reopened.dismiss();assertNull(result)
        controller.pause().stop().destroy()
        val recreated=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val restored=ImprintDialog.show(recreated.get(),EditorState(imprint=old),meta(),draft){result=it}
        idleUi()
        assertEquals(88,restored.window!!.decorView.findViewWithTag<SeekBar>("imprint-size").progress)
        assertTrue(restored.window!!.decorView.findViewWithTag<Switch>("imprint-placement").isChecked)
        restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(old.copy(size=1.48,datePlacement="edge"),result)
        result=null
        val back=ImprintDialog.show(recreated.get(),EditorState(imprint=old),meta()){result=it}
        idleUi()
        back.onBackPressed();assertNull(result);assertFalse(back.isShowing)
        recreated.pause().stop().destroy()
    }
    @Test fun missingDateRequiresOnlyExplicitDateAndNoTimeControls() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            var applied:ImprintSettings?=null
            val missing=PhotoMetadata.fromAttributes(emptyMap())
            val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),missing){applied=it}
            idleUi();assertNoTextEntry(d)
            assertNull(d.window!!.decorView.findViewWithTag<View>("imprint-time-value"))
            assertNull(d.window!!.decorView.findViewWithTag<View>("imprint-time-note"))
            assertNull(ImprintDialog.capture(d).getString("imprint-date"))
            chooseDate(d,2024,2,29)
            click(d,AlertDialog.BUTTON_POSITIVE)
            assertEquals("'24 02 29",ImprintContent.from(missing,checkNotNull(applied)).date)
            assertNull(ImprintContent.originalCaptureDateTime(missing))
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun datePickerPreservesSourceTimeInternallyButPrintsDateOnly() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val metadata=meta();val frozen=metadata.toJson();var applied:ImprintSettings?=null
            val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),metadata){applied=it}
            idleUi();chooseDate(d,2000,2,29);click(d,AlertDialog.BUTTON_POSITIVE)
            assertEquals(setting.copy(manualDateTime="2000-02-29 00:01:02"),applied)
            assertEquals("'00 02 29",ImprintContent.from(metadata,checkNotNull(applied)).date)
            assertEquals(frozen,metadata.toJson())
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun childPickerCancelAndBackLeaveDraftAndSourceUntouched() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            var applied:ImprintSettings?=null
            val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),meta()){applied=it}
            idleUi()
            for(useBack in listOf(false,true)) {
                val child=datePicker(d).also{it.updateDate(2000,1,29)}
                val pending=ImprintDialog.capture(d)
                assertEquals("2025-04-28 00:01:02",pending.getString("imprint-datetime"));assertFalse(pending.getBoolean("imprint-manual"))
                assertEquals("date",pending.getString("imprint-picker"))
                assertNotNull(pending.getBundle("imprint-picker-state"))
                if(useBack){child.onBackPressed();idleUi()}else click(child,AlertDialog.BUTTON_NEGATIVE)
                assertFalse(child.isShowing);assertTrue(d.isShowing)
                val cancelled=ImprintDialog.capture(d)
                assertEquals("2025-04-28 00:01:02",cancelled.getString("imprint-datetime"));assertFalse(cancelled.getBoolean("imprint-manual"))
                assertNull(cancelled.getString("imprint-picker"));assertNull(applied)
            }
            click(d,AlertDialog.BUTTON_POSITIVE);assertEquals(setting,applied)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun parentCancelAndBackDismissPendingNativePickerWithoutApplying() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            var applied:ImprintSettings?=null
            for(useBack in listOf(false,true)) {
                val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),meta()){applied=it}
                idleUi()
                val child=datePicker(d).also{it.updateDate(2000,1,29)}
                if(useBack){d.onBackPressed();idleUi()}else click(d,AlertDialog.BUTTON_NEGATIVE)
                assertFalse(d.isShowing);assertFalse("Parent dismissal must close its native picker",child.isShowing);assertNull(applied)
            }
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun chosenDateSurvivesRecreationWithoutRequiringTime() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            var applied:ImprintSettings?=null
            val missing=PhotoMetadata.unavailable()
            val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),missing){applied=it}
            idleUi();chooseDate(d,2000,2,29)
            val draft=ImprintDialog.capture(d);d.dismiss()
            val restored=ImprintDialog.show(controller.get(),EditorState(imprint=setting),missing,draft){applied=it}
            idleUi();assertEquals("2000-02-29",ImprintDialog.capture(restored).getString("imprint-date"))
            click(restored,AlertDialog.BUTTON_POSITIVE)
            assertEquals("'00 02 29",ImprintContent.from(missing,checkNotNull(applied)).date)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun pendingNativeDateSelectionSurvivesRecreationAndStillNeedsConfirmation() {
        val first=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        var applied:ImprintSettings?=null
        val missing=PhotoMetadata.unavailable()
        val d=ImprintDialog.show(first.get(),EditorState(imprint=setting),missing){applied=it}
        idleUi()
        val picker=datePicker(d);picker.updateDate(2000,1,29)
        val draft=ImprintDialog.capture(d)
        assertNull(draft.getString("imprint-date"));assertNull(draft.getString("imprint-time"))
        assertFalse(draft.getBoolean("imprint-manual"));assertEquals("date",draft.getString("imprint-picker"))
        assertNotNull(draft.getBundle("imprint-picker-state"))
        d.dismiss();idleUi();assertFalse(picker.isShowing);first.pause().stop().destroy()
        val second=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val restored=ImprintDialog.show(second.get(),EditorState(imprint=setting),missing,draft){applied=it}
            idleUi()
            val pending=ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog
            assertNotSame(picker,pending);assertTrue(pending.isShowing)
            assertEquals(2000,pending.datePicker.year);assertEquals(1,pending.datePicker.month);assertEquals(29,pending.datePicker.dayOfMonth)
            assertNull(ImprintDialog.capture(restored).getString("imprint-date"));assertNull(applied)
            click(pending,AlertDialog.BUTTON_POSITIVE)
            assertEquals("2000-02-29",ImprintDialog.capture(restored).getString("imprint-date"))
            click(restored,AlertDialog.BUTTON_POSITIVE)
            assertEquals(setting.copy(manualDateTime="2000-02-29 00:00:00"),applied)
        }finally{second.pause().stop().destroy()}
    }
    @Test fun legacyCanonicalDraftMigratesButInvalidOrOversizedDraftCannotCommit() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            var applied:ImprintSettings?=null
            val legacy=android.os.Bundle().apply {
                putString("imprint-datetime","2000-02-29 07:08:09");putBoolean("imprint-manual",true)
            }
            val valid=ImprintDialog.show(controller.get(),EditorState(imprint=setting),meta(),legacy){applied=it}
            idleUi();assertNoTextEntry(valid)
            val retained=ImprintDialog.capture(valid)
            assertEquals("2000-02-29",retained.getString("imprint-date"));assertEquals("07:08:09",retained.getString("imprint-time"))
            click(valid,AlertDialog.BUTTON_POSITIVE)
            assertEquals(setting.copy(manualDateTime="2000-02-29 07:08:09"),applied)
            for(raw in listOf("2025-02-30 12:34:56","x".repeat(5000))) {
                applied=null
                val invalid=android.os.Bundle().apply{putString("imprint-datetime",raw);putBoolean("imprint-manual",true)}
                val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),meta(),invalid){applied=it}
                idleUi()
                val captured=ImprintDialog.capture(d)
                assertTrue("Invalid restored text must not turn into a source or current date",captured.getString("imprint-datetime").isNullOrEmpty())
                assertNull(captured.getString("imprint-date"))
                assertEquals(if(raw.startsWith("2025-02-30 "))"12:34:56" else null,captured.getString("imprint-time"))
                assertTrue(captured.getBoolean("imprint-manual"))
                assertTrue((captured.getString("imprint-datetime")?.length ?: 0)<=19)
                assertIncompleteCannotApply(d);assertNull(applied)
                if(raw.startsWith("2025-02-30 ")) {
                    chooseDate(d,2024,2,29)
                    assertEquals("2024-02-29 12:34:56",ImprintDialog.capture(d).getString("imprint-datetime"))
                    assertNull(applied)
                }
                d.window!!.decorView.findViewWithTag<View>("imprint-restore-capture").performClick();idleUi()
                assertEquals("2025-04-28 00:01:02",ImprintDialog.capture(d).getString("imprint-datetime"))
                click(d,AlertDialog.BUTTON_POSITIVE);assertEquals(setting,applied)
            }
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun missingCaptureUsesModifiedOrCreatedDateWithoutSourceCaption() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            for((attributes,expected,kind) in listOf(
                Triple(mapOf("DateTimeDigitized" to "2024:01:01 00:00:00","DateTime" to "2025:01:01 00:00:00"),"'25 01 01",ImprintText.MODIFIED),
                Triple(mapOf("DateTimeOriginal" to "2025:02:30 12:34:56","DateTimeDigitized" to "2024:01:01 00:00:00"),"'24 01 01",ImprintText.CREATED)
            )) {
                val metadata=PhotoMetadata.fromAttributes(attributes);var applied:ImprintSettings?=null
                val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),metadata){applied=it}
                idleUi();click(datePicker(d),AlertDialog.BUTTON_NEGATIVE)
                assertNull(d.window!!.decorView.findViewWithTag<View>("imprint-source"))
                click(d,AlertDialog.BUTTON_POSITIVE);assertEquals(setting,applied)
                assertEquals(expected,ImprintContent.from(metadata,checkNotNull(applied)).date)
                assertEquals(expected,ImprintContent.from(PhotoMetadata.fromJson(metadata.toJson()),setting).date)
                assertNull(ImprintContent.originalCaptureDateTime(metadata))
            }
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun nativeDatePickerPreservesYearEndpointsAndModernLeapDates() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            for(date in listOf(java.time.LocalDate.of(1,1,1),java.time.LocalDate.of(1600,2,29),java.time.LocalDate.of(1900,2,28),java.time.LocalDate.of(2000,2,29),java.time.LocalDate.of(9999,12,31))) {
                val year=date.year;val month=date.monthValue;val day=date.dayOfMonth;val expected=date.toString()
                var applied:ImprintSettings?=null
                val d=ImprintDialog.show(controller.get(),EditorState(imprint=setting),meta()){applied=it}
                idleUi()
                val picker=datePicker(d)
                picker.updateDate(year,month-1,day)
                assertEquals(year,picker.datePicker.year);assertEquals(month-1,picker.datePicker.month);assertEquals(day,picker.datePicker.dayOfMonth)
                click(picker,AlertDialog.BUTTON_POSITIVE)
                val captured=ImprintDialog.capture(d)
                assertEquals(expected,captured.getString("imprint-date"))
                assertEquals("$expected 00:01:02",captured.getString("imprint-datetime"))
                val reopened=datePicker(d)
                assertEquals(year,reopened.datePicker.year);assertEquals(month-1,reopened.datePicker.month);assertEquals(day,reopened.datePicker.dayOfMonth)
                click(reopened,AlertDialog.BUTTON_NEGATIVE)
                assertEquals("$expected 00:01:02",ImprintDialog.capture(d).getString("imprint-datetime"))
                click(d,AlertDialog.BUTTON_POSITIVE)
                assertEquals(setting.copy(manualDateTime="$expected 00:01:02"),applied)
            }
        }finally{controller.pause().stop().destroy()}
    }

}
