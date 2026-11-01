package dev.filmroom.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** Application-owned resources only; never retains an Activity or rewrites rendered UI text. */
object L10n {
    private const val PREFS="filmroom-language"
    private const val LANGUAGE="language"
    private const val CHOSEN="chosen"
    val supported=listOf("zh","en","ja","ko")
    val nativeNames=listOf("中文","English","日本語","한국어")
    const val multilingualTitle="语言 / Language / 言語 / 언어"
    const val multilingualDescription="更改应用语言 · Change app language\nアプリの言語を変更 · 앱 언어 변경"
    @Volatile private var localizedResources:Resources?=null
    @Volatile private var applicationContext:Context?=null
    @Volatile private var currentTag="zh"
    private fun locale(tag:String):Locale=Locale.forLanguageTag(if(tag=="zh")"zh-CN" else tag)
    fun language(context:Context):String=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(LANGUAGE,"zh").let{if(it in supported)it!! else "zh"}
    fun hasChosen(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(CHOSEN,false)
    fun wrap(context:Context):Context {
        val config=Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale(language(context))))
        return context.createConfigurationContext(config)
    }
    fun initialize(context:Context){
        currentTag=language(context)
        applicationContext=context.applicationContext ?: context
        localizedResources=wrap(checkNotNull(applicationContext)).resources
    }
    private fun currentResources():Resources? {
        val cached=localizedResources
        // Framework configuration updates can invalidate resource configurations while the app lives.
        // Rebuild the explicit app-locale context rather than falling back to the device language.
        if(cached!=null && cached.configuration.locales[0].language!=currentTag){
            applicationContext?.let { localizedResources=wrap(it).resources }
        }
        return localizedResources
    }
    fun choose(context:Context,tag:String){
        require(tag in supported)
        // Synchronous tiny preference write ensures a subsequent Activity recreation sees this choice.
        check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(LANGUAGE,tag).putBoolean(CHOSEN,true).commit())
        initialize(context)
        runCatching{dev.filmroom.export.ExportService.refreshExistingChannel(context.applicationContext ?: context)}
    }
    fun format(template:String,vararg args:Any?):String=String.format(locale(currentTag),template,*args)
    fun text(id:Int,vararg args:Any?):String {
        val template=currentResources()?.let{runCatching{it.getString(id)}.getOrNull()} ?: ChineseStrings.values[id] ?: ""
        return if(args.isEmpty())template else String.format(locale(currentTag),template,*args)
    }
}
