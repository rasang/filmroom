import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.filmroom"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "dev.filmroom"; minSdk = 29; targetSdk = 35; versionCode = 89; versionName = "0.1.37-beta37"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    // Optional distribution ABI filter; default source build retains both tested ABIs.
    providers.gradleProperty("filmroomAbi").orNull?.let { abi ->
        require(abi in setOf("arm64-v8a", "x86_64")) { "Unsupported filmroomAbi" }
        defaultConfig.ndk.abiFilters += abi
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "m4a" }
    packaging {
        // Compress DEX and native transport bytes without changing library bits.
        // Android extracts JNI libraries on install; ELF remains 16 KiB aligned.
        dex.useLegacyPackaging = true
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libfilm_engine.so", "**/libfilm_codecs.so")
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("filmroomHighresFixtures").orNull?.let { systemProperty("filmroom.highres.fixtures", it) }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    listOf("http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort","http.nonProxyHosts").forEach { key -> System.getProperty(key)?.let { systemProperty(key,it) } }
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.net=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}

// Isolated candidate: transport compression only, original source asset remains untouched.
val packedAssets = layout.buildDirectory.dir("generated/packedAssets")
val preparePackedEngineAssets by tasks.registering(Sync::class) {
    from("src/main/assets")
    exclude("engine-data/luts/spectral_upsampling/irradiance_xy_tc.npy")
    into(packedAssets)
    inputs.file("src/main/assets/engine-data/luts/spectral_upsampling/irradiance_xy_tc.npy")
    doLast {
        val original=file("src/main/assets/engine-data/luts/spectral_upsampling/irradiance_xy_tc.npy").readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(original)
        check(original.size==5972096 && hash.joinToString(""){"%02x".format(it)}=="52f90c724811f5fab80769f8548bf24d08a521eb09e65ab6e016893258d2a783")
        val count=(original.size-128)/2;val shuffled=ByteArray(count*2);val previous=IntArray(81)
        for(i in 0 until count){val word=(original[128+2*i].toInt() and 255) or ((original[129+2*i].toInt() and 255) shl 8);val lane=i%81;val delta=(word-previous[lane]) and 65535;previous[lane]=word;shuffled[i]=delta.toByte();shuffled[count+i]=(delta ushr 8).toByte()}
        val output=packedAssets.get().file("engine-data/luts/spectral_upsampling/irradiance_xy_tc.frlut").asFile
        output.parentFile.mkdirs()
        output.outputStream().use { stream ->
            stream.write("FRLUT001".toByteArray(Charsets.US_ASCII))
            stream.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(original.size).putInt(128).putInt(81).array())
            stream.write(original,0,128);stream.write(hash)
            val deflater=Deflater(9)
            try {val compressed=DeflaterOutputStream(stream,deflater);compressed.write(shuffled);compressed.finish()}finally{deflater.end()}
        }
    }
}
android.sourceSets.getByName("main").assets.setSrcDirs(listOf(packedAssets))
tasks.configureEach { if(name.matches(Regex("merge.*Assets")) || name.contains("lint",ignoreCase=true)) dependsOn(preparePackedEngineAssets) }
tasks.withType<Test>().configureEach {
    dependsOn(preparePackedEngineAssets)
    systemProperty("filmroom.lut.original",file("src/main/assets/engine-data/luts/spectral_upsampling/irradiance_xy_tc.npy").absolutePath)
    systemProperty("filmroom.lut.packed",packedAssets.get().file("engine-data/luts/spectral_upsampling/irradiance_xy_tc.frlut").asFile.absolutePath)
}

// A dedicated host gate avoids relying on a manually selected release test list.
// Reuse AGP's debug unit-test inputs/resources, never a previously packaged APK.
val verifySavedPresetCompatibility by tasks.registering(Test::class) {
    group = "verification"
    description = "Verify recursive preset field coverage, persistence, legacy QR import, and UI compatibility before release packaging."
    val hostTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(provider { hostTests.taskDependencies.getDependencies(hostTests) })
    testClassesDirs = hostTests.testClassesDirs
    classpath = hostTests.classpath
    systemProperties(hostTests.systemProperties)
    workingDir = hostTests.workingDir
    maxParallelForks = 1
    forkEvery = 1
    maxHeapSize = "384m"
    jvmArgs("-XX:ActiveProcessorCount=2", "-XX:MaxMetaspaceSize=256m", "-XX:+UseSerialGC")
    val requiredClasses = setOf(
        "dev.filmroom.presets.SavedPresetCoverageHostTest",
        "dev.filmroom.presets.SavedPresetStoreHostTest",
        "dev.filmroom.SavedPresetsUiHostTest",
        "dev.filmroom.SavedPresetListPerformanceHostTest",
        "dev.filmroom.SavedPresetIntegratedProfilesHostTest",
        "dev.filmroom.presets.PresetQrCodecHostTest",
        "dev.filmroom.presets.PresetQrUiHostTest",
        "dev.filmroom.presets.PresetQrCompactHostTest",
        "dev.filmroom.presets.PresetQrLogoHostTest",
        "dev.filmroom.presets.RecipeShareDialogHostTest",
        "dev.filmroom.presets.IndependentMediaSelectionHostTest",
        "dev.filmroom.presets.RecipeScannerHostTest",
        "dev.filmroom.backup.AppBackupCodecHostTest",
        "dev.filmroom.backup.BackupSettingsUiHostTest",
        "dev.filmroom.export.ExportHistoryBackupHostTest",
        "dev.filmroom.backup.BackupMediaCompositionCrossHostTest",
    )
    filter {
        requiredClasses.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
    val executedClasses = mutableSetOf<String>()
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun beforeTest(test: TestDescriptor) = Unit
        override fun afterTest(test: TestDescriptor, result: TestResult) {
            check(result.resultType != TestResult.ResultType.SKIPPED) { "Preset gate skipped ${test.className}.${test.name}" }
            test.className?.let { executedClasses.add(it) }
        }
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
    })
    doLast {
        check(executedClasses.containsAll(requiredClasses)) { "Preset gate did not execute: ${requiredClasses - executedClasses}" }
    }
    // A release must execute the gate, not trust a stale UP-TO-DATE test receipt.
    outputs.upToDateWhen { false }
}
tasks.configureEach {
    if (name in setOf("check", "packageRelease", "packageReleaseBundle", "assembleRelease", "bundleRelease")) {
        dependsOn(verifySavedPresetCompatibility)
    }
}
