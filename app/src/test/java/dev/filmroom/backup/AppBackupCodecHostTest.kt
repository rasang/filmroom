package dev.filmroom.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.model.EditorState
import dev.filmroom.presets.SavedPresetStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=Application::class)
class AppBackupCodecHostTest {
    private fun document() = AppBackupCodec.Document(JSONObject().put("resetOnImport",true), listOf(
        SavedPresetStore.Preset(UUID.randomUUID().toString(),"我的配方",123L,SavedPresetStore.snapshot(EditorState()))), JSONArray())
    private fun bad(block:()->Unit) { try { block(); fail("Must reject") } catch(_:Exception) {} }
    @Test fun roundTripKeepsRecipeParametersAndSettings() {
        val input=document(); val output=AppBackupCodec.read(AppBackupCodec.encode(input).inputStream())
        assertEquals(input.recipes.first().parameters(),output.recipes.first().parameters())
        assertTrue(output.settings.getBoolean("resetOnImport"))
    }
    @Test fun unknownAndWrongKindProfilesRejected() {
        val state=document().recipes.first().parameters()
        val profiles=JSONArray().put(JSONObject().put("id",state.filmStock).put("kind","negative"))
            .put(JSONObject().put("id",state.paperStock).put("kind","paper"))
        AppBackupCodec.validateStocks(document(),profiles)
        bad{AppBackupCodec.validateStocks(document(),JSONArray())}
        profiles.getJSONObject(0).put("kind","paper")
        bad{AppBackupCodec.validateStocks(document(),profiles)}
    }
    @Test fun explicitNoneProfilesAreAllowedButUnknownProfilesStillReject() {
        val base = document()
        val emptyCatalog = JSONArray()
        val noneRecipe = base.recipes.single().copy(snapshot=SavedPresetStore.snapshot(EditorState(filmStock="none",paperStock="none")))
        AppBackupCodec.validateStocks(base.copy(recipes=listOf(noneRecipe)),emptyCatalog)
        val unknownRecipe = noneRecipe.copy(snapshot=SavedPresetStore.snapshot(EditorState(filmStock="future_unknown",paperStock="none")))
        bad { AppBackupCodec.validateStocks(base.copy(recipes=listOf(unknownRecipe)),emptyCatalog) }
    }
    @Test fun unknownVersionRejected() { val root=JSONObject(String(AppBackupCodec.encode(document())));root.put("version",99);bad{AppBackupCodec.decode(root)} }
    @Test fun duplicateKeysAndTrailingDataRejected() {
        bad{AppBackupCodec.read("{\"type\":\"a\",\"type\":\"b\"}".byteInputStream())}
        bad{AppBackupCodec.read((String(AppBackupCodec.encode(document()))+"{} ").byteInputStream())}
    }
    @Test fun oversizedAndMalformedUtf8Rejected() {
        bad{AppBackupCodec.read(ByteArray(AppBackupCodec.MAX_BYTES+1).inputStream())}
        try{AppBackupCodec.read(byteArrayOf(0xc3.toByte(),0x28).inputStream());fail()}catch(_:java.nio.charset.CharacterCodingException){}
    }
    @Test fun invalidSettingsAreNotCoerced() {
        bad{BackupSettings.validate(JSONObject().put("restoreLastPhoto","true"))}
        bad{BackupSettings.validate(JSONObject().put("rawBatchMode","huge"))}
        bad{BackupSettings.validate(JSONObject().put("source","/private/photo.jpg"))}
    }
    @Test fun snapshotsExcludeSessionUrisCachesAndUnexpectedPreferences() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("editor-preferences",0).edit().putString("source","file:///private/secret.jpg").commit()
        context.getSharedPreferences("local-session",0).edit().putString("source","private-source").commit()
        val output=BackupSettings.snapshot(context).toString()
        assertFalse(output.contains("secret")); assertFalse(output.contains("source"));assertFalse(output.contains("private"))
    }
    @Test fun missingOldSettingsKeepCurrentValueAndRestoreExplicitSwitch() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("editor-preferences",0).edit().putBoolean("restore-last-photo",true).commit()
        BackupSettings.apply(context,JSONObject().put("resetOnImport",true))
        val values=context.getSharedPreferences("editor-preferences",0)
        assertTrue(values.getBoolean("reset-on-import",false));assertTrue(values.getBoolean("restore-last-photo",false))
    }
    @Test fun futureRecipeFieldsRejectWithoutWriting() {
        val root=JSONObject(String(AppBackupCodec.encode(document())))
        root.getJSONArray("recipes").getJSONObject(0).getJSONObject("parameters").put("unknownFutureParameter",true)
        try{AppBackupCodec.decode(root);fail()}catch(_:Exception){}
    }
    @Test fun restoreMergePreservesExistingRecipesAndRenamesConflictsAndDeduplicatesRetry() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val file=java.io.File(context.cacheDir,"merge-${UUID.randomUUID()}.json")
        val store=SavedPresetStore(file)
        val original=store.save("我的配方",EditorState())
        val item=document().recipes.first().copy(snapshot=SavedPresetStore.snapshot(EditorState(brightness=.5f)))
        assertEquals(1,store.mergeBackup(listOf(item)))
        assertEquals(0,store.mergeBackup(listOf(item)))
        assertEquals(2,store.list().size)
        assertEquals(original,store.list().first())
        assertEquals("我的配方 (2)",store.list().last().name)
    }

    @Test fun zipAndLegacyJsonRoundTrip() {
        val out=java.io.ByteArrayOutputStream();AppBackupCodec.writeZip(document(),out)
        assertEquals(80,out.toByteArray()[0].toInt())
        assertEquals(document().settings.toString(),AppBackupCodec.read(out.toByteArray().inputStream()).settings.toString())
        val legacy=JSONObject(String(AppBackupCodec.encode(document()))).put("version",1).toString()
        assertEquals(1,AppBackupCodec.read(legacy.byteInputStream()).recipes.size)
    }
    @Test fun zipRejectsTraversalUnexpectedDuplicateAndOversizedEntries() {
        fun archive(vararg entries:Pair<String,ByteArray>):ByteArray {
            val out=java.io.ByteArrayOutputStream();java.util.zip.ZipOutputStream(out).use { z->
                entries.forEach { (name,bytes)->z.putNextEntry(java.util.zip.ZipEntry(name));z.write(bytes);z.closeEntry() }
            };return out.toByteArray()
        }
        val valid=AppBackupCodec.encode(document())
        bad{AppBackupCodec.read(archive("../backup.json" to valid).inputStream())}
        bad{AppBackupCodec.read(archive("backup.json" to valid,"extra.jpg" to byteArrayOf(1)).inputStream())}
        bad{AppBackupCodec.read(archive("backup.json" to ByteArray(AppBackupCodec.MAX_BYTES+1){32}).inputStream())}
    }
}
