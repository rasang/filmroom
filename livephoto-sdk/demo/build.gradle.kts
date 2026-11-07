plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.filmroom.livephoto.demo"
    compileSdk = 36
    defaultConfig { applicationId = "dev.filmroom.livephoto.demo"; minSdk = 29; targetSdk = 36; versionCode = 2; versionName = "0.1.0-alpha02" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
dependencies { implementation(project(":livephoto-android")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") }
