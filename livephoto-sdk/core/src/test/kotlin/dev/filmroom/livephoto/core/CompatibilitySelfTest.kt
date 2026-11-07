package dev.filmroom.livephoto.core

import java.io.File
import java.util.Locale

/** Platform-free policy/storage regression; no Android or OEM gallery compatibility claim. */
fun compatibilitySelfTests(root: File): List<Triple<String,Boolean,String>> {
    root.mkdirs()
    val results=mutableListOf<Triple<String,Boolean,String>>()
    var now=1_800_000_000_000L
    val hash="a".repeat(64)
    val env=TargetEnvironment("vivo","vivo","sample-model","sample/build/1",36,"test.gallery",100,"sdr-first-audio")
    fun test(name:String,block:()->Unit) {
        try { block(); results+=Triple("compatibility: $name",true,""); println("PASS compatibility: $name") }
        catch(t:Throwable) { results+=Triple("compatibility: $name",false,t.toString()); t.printStackTrace() }
    }
    fun rejects(block:()->Unit) { var caught=false; try { block() } catch(_:Exception) { caught=true }; check(caught) }
    fun policy(name:String):Pair<ProfileResolver,FileCompatibilityStore> {
        val store=FileCompatibilityStore(File(root,name)); return ProfileResolver(store){now} to store
    }
    val aliases=mapOf("OPPO" to TargetBrand.OPLUS,"OnePlus" to TargetBrand.OPLUS,"realme" to TargetBrand.OPLUS,
        "vivo" to TargetBrand.VIVO,"iQOO" to TargetBrand.VIVO,"Xiaomi" to TargetBrand.XIAOMI,
        "Redmi" to TargetBrand.XIAOMI,"POCO" to TargetBrand.XIAOMI,"HONOR" to TargetBrand.HONOR,
        "samsung" to TargetBrand.SAMSUNG,"unknown" to TargetBrand.OTHER)
    for((name,brand) in aliases) test("brand $name is a hint only") {
        check(ProfileResolver.brandOf("unknown",name)==brand)
        val (p,_)=policy("brand-$name")
        val s=p.resolve(brand,env)
        check(s.status==SupportStatus.UNKNOWN && s.candidates.all { it.status==SupportStatus.UNKNOWN })
    }
    test("manufacturer fallback and no substring matching") {
        check(ProfileResolver.brandOf(" Samsung ","")==TargetBrand.SAMSUNG)
        check(ProfileResolver.brandOf("not-vivo","not-oppo")==TargetBrand.OTHER)
        check(ProfileResolver.brandOf("vivo","HONOR")==TargetBrand.HONOR)
    }
    test("brand normalization is locale independent") {
        val previous=Locale.getDefault()
        try { Locale.setDefault(Locale("tr","TR"));check(ProfileResolver.brandOf("","XIAOMI")==TargetBrand.XIAOMI) }
        finally { Locale.setDefault(previous) }
    }
    test("vivo no model or OS threshold") {
        val (p,_)=policy("threshold")
        for(model in listOf("X100","X200","X300","X999")) {
            val s=p.resolve(TargetBrand.VIVO,env.copy(model=model,sdkInt=37))
            check(s.selectedProfile==LivePhotoProfile.VIVO_SINGLE && s.status==SupportStatus.UNKNOWN)
        }
    }
    test("Samsung 107 then 106 then Google") {
        check(ProfileResolver.candidates(TargetBrand.SAMSUNG)==listOf(LivePhotoProfile.SAMSUNG_SEF_107,LivePhotoProfile.SAMSUNG_SEF_106,LivePhotoProfile.GOOGLE))
    }
    test("vivo negative only removes one candidate") {
        val(p,_)=policy("fallback")
        p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.UNSUPPORTED,hash)
        val next=p.resolve(TargetBrand.VIVO,env)
        check(next.selectedProfile==LivePhotoProfile.VIVO_PAIR_2104 && next.status==SupportStatus.UNKNOWN)
        check(next.candidates.single { it.profile==LivePhotoProfile.VIVO_SINGLE }.status==SupportStatus.UNSUPPORTED)
    }
    test("remembered pair succeeds after a store restart") {
        val(p,_)=policy("restart")
        val s=p.resolve(TargetBrand.VIVO,env,LivePhotoProfile.VIVO_PAIR_2104)
        p.remember(s,SupportStatus.SUPPORTED,hash)
        val (again,_)=policy("restart")
        check(again.resolve(TargetBrand.VIVO,env).let { it.selectedProfile==s.selectedProfile && it.status==SupportStatus.SUPPORTED && it.reason==SelectionReason.USER_CONFIRMED })
    }
    test("most recently confirmed passing profile wins") {
        val(p,_)=policy("latest")
        p.remember(p.resolve(TargetBrand.VIVO,env,LivePhotoProfile.VIVO_SINGLE),SupportStatus.SUPPORTED,hash)
        now++
        p.remember(p.resolve(TargetBrand.VIVO,env,LivePhotoProfile.VIVO_PAIR_LEGACY),SupportStatus.SUPPORTED,hash)
        check(p.resolve(TargetBrand.VIVO,env).selectedProfile==LivePhotoProfile.VIVO_PAIR_LEGACY)
    }
    test("known failure can be manually retried without overwriting record") {
        val(p,_)=policy("manual")
        p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.UNSUPPORTED,hash)
        val s=p.resolve(TargetBrand.VIVO,env,LivePhotoProfile.VIVO_SINGLE)
        check(s.reason==SelectionReason.MANUAL && s.status==SupportStatus.UNSUPPORTED && s.selectedProfile==LivePhotoProfile.VIVO_SINGLE)
        check(p.resolve(TargetBrand.VIVO,env).selectedProfile==LivePhotoProfile.VIVO_PAIR_2104)
    }
    test("all candidates failed does not pick a failing default") {
        val(p,_)=policy("all-negative")
        for(profile in ProfileResolver.candidates(TargetBrand.VIVO))
            p.remember(p.resolve(TargetBrand.VIVO,env,profile),SupportStatus.UNSUPPORTED,hash)
        val s=p.resolve(TargetBrand.VIVO,env)
        check(s.selectedProfile==null && s.status==SupportStatus.UNSUPPORTED && s.reason==SelectionReason.NO_CANDIDATE)
    }
    test("remote export does not reuse local evidence") {
        val(p,_)=policy("remote")
        p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.SUPPORTED,hash)
        check(p.resolve(TargetBrand.VIVO).status==SupportStatus.UNKNOWN)
        rejects { p.remember(p.resolve(TargetBrand.VIVO),SupportStatus.SUPPORTED,hash) }
    }
    test("missing gallery version stays unknown and cannot be cached") {
        val(p,_)=policy("missing")
        p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.SUPPORTED,hash)
        val unknown=p.resolve(TargetBrand.VIVO,env.copy(galleryVersion=null))
        check(unknown.status==SupportStatus.UNKNOWN)
        rejects { p.remember(unknown,SupportStatus.SUPPORTED,hash) }
    }
    test("missing fingerprint cannot be cached") {
        val(p,_)=policy("missing-build")
        rejects { p.remember(p.resolve(TargetBrand.VIVO,env.copy(buildFingerprint="unknown")),SupportStatus.UNSUPPORTED,hash) }
    }
    val changes=mapOf("gallery upgrade" to env.copy(galleryVersion=101),"gallery switch" to env.copy(galleryPackage="other.gallery"),
        "ROM upgrade" to env.copy(buildFingerprint="sample/build/2"),"different model" to env.copy(model="other"),
        "writer revision" to env.copy(writerRevision="next"),"media settings" to env.copy(mediaSettingsKey="hdr"),
        "SDK version" to env.copy(sdkInt=37))
    for((name,changed) in changes) test("$name invalidates old evidence") {
        val(p,_)=policy(name.replace(' ','-'))
        p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.SUPPORTED,hash)
        check(changed.key!=env.key && p.resolve(TargetBrand.VIVO,changed).status==SupportStatus.UNKNOWN)
    }
    test("cache expires after 90 days") {
        val(p,store)=policy("expired")
        store.write(CompatibilityRecord(env.key,LivePhotoProfile.VIVO_SINGLE,SupportStatus.SUPPORTED,now-ProfileResolver.RECORD_TTL_MS-1,hash))
        check(p.resolve(TargetBrand.VIVO,env).status==SupportStatus.UNKNOWN)
    }
    test("future timestamp is not trusted") {
        val(p,store)=policy("future")
        store.write(CompatibilityRecord(env.key,LivePhotoProfile.VIVO_SINGLE,SupportStatus.SUPPORTED,now+1,hash))
        check(p.resolve(TargetBrand.VIVO,env).status==SupportStatus.UNKNOWN)
    }
    test("unknown explicitly clears just that profile") {
        val(p,_)=policy("forget")
        val s=p.resolve(TargetBrand.VIVO,env)
        p.remember(s,SupportStatus.SUPPORTED,hash);p.remember(s,SupportStatus.UNKNOWN,hash)
        check(p.resolve(TargetBrand.VIVO,env).status==SupportStatus.UNKNOWN)
    }
    test("broken record is unknown not a blacklist") {
        val(p,_)=policy("corrupt")
        val s=p.resolve(TargetBrand.VIVO,env);p.remember(s,SupportStatus.SUPPORTED,hash)
        File(root,"corrupt/${env.key}-VIVO_SINGLE.properties").writeText("status=UNSUPPORTED\n")
        check(p.resolve(TargetBrand.VIVO,env).status==SupportStatus.UNKNOWN)
    }
    test("oversized record is ignored") {
        val(p,_)=policy("large")
        p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.SUPPORTED,hash)
        File(root,"large/${env.key}-VIVO_SINGLE.properties").appendText("a".repeat(5000))
        check(p.resolve(TargetBrand.VIVO,env).status==SupportStatus.UNKNOWN)
    }
    test("invalid hash cannot be recorded") {
        val(p,_)=policy("hash"); rejects { p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.SUPPORTED,"bad") }
    }
    test("no path traversal in store keys") { val(_,s)=policy("path");rejects { s.read("../other",LivePhotoProfile.GOOGLE) } }
    test("clear deletes capability records only") {
        val(p,_)=policy("clear");p.remember(p.resolve(TargetBrand.VIVO,env),SupportStatus.SUPPORTED,hash)
        val sentinel=File(root,"clear/photo.jpg");sentinel.writeText("keep")
        p.clear();check(sentinel.readText()=="keep" && p.resolve(TargetBrand.VIVO,env).status==SupportStatus.UNKNOWN)
    }
    for(hint in SupportStatus.values()) test("vendor $hint remains advisory for custom profiles") {
        val(p,_)=policy("hint-$hint")
        val s=p.resolve(TargetBrand.XIAOMI,env,deviceHint=DeviceSupportHint(hint,"test","not a gallery test"))
        check(s.status==SupportStatus.UNKNOWN && s.deviceHint?.status==hint)
    }
    test("non-first cover does not auto-select remembered legacy vivo") {
        val(p,_)=policy("cover")
        p.remember(p.resolve(TargetBrand.VIVO,env,LivePhotoProfile.VIVO_PAIR_LEGACY),SupportStatus.SUPPORTED,hash)
        val s=p.resolve(TargetBrand.VIVO,env,firstFrame=false)
        check(s.candidates.none { it.profile==LivePhotoProfile.VIVO_PAIR_LEGACY } && s.status==SupportStatus.UNKNOWN)
        rejects { p.resolve(TargetBrand.VIVO,env,LivePhotoProfile.VIVO_PAIR_LEGACY,firstFrame=false) }
    }
    test("environment encoding does not collide on delimiters") {
        check(env.copy(model="a:b",buildFingerprint="c").key!=env.copy(model="a",buildFingerprint="b:c").key)
    }
    test("manually confirmed cross-brand profile is remembered") {
        val(p,_)=policy("cross-profile")
        p.remember(p.resolve(TargetBrand.OPLUS,env,LivePhotoProfile.SAMSUNG_SEF_107),SupportStatus.SUPPORTED,hash)
        val s=p.resolve(TargetBrand.OPLUS,env)
        check(s.selectedProfile==LivePhotoProfile.SAMSUNG_SEF_107 && s.status==SupportStatus.SUPPORTED)
    }
    test("selection alone never writes success") {
        val(p,_)=policy("no-auto-success");repeat(5) { check(p.resolve(TargetBrand.VIVO,env).status==SupportStatus.UNKNOWN) }
        check(!File(root,"no-auto-success").exists())
    }
    return results
}

fun main(args:Array<String>) {
    require(args.size==1)
    val out=File(args[0]);out.mkdirs()
    val results=compatibilitySelfTests(File(out,"cases"))
    fun q(s:String)=FlatJson.encode(mapOf("s" to s)).removePrefix("{\"s\":").removeSuffix("}")
    File(out,"compatibility-results.json").writeText(results.joinToString(",\n","[\n","\n]") {
        (name,passed,error)->"{\"name\":${q(name)},\"passed\":$passed,\"error\":${q(error)}}"
    })
    val passed=results.count { it.second }
    println("RESULT $passed/${results.size}")
    check(passed==results.size)
}
