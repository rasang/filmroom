plugins { kotlin("jvm"); `java-library`; `maven-publish` }
kotlin { jvmToolchain(17); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11; withSourcesJar() }
tasks.register<JavaExec>("selfTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.filmroom.livephoto.core.CoreSelfTestKt")
    args(layout.buildDirectory.dir("selftest").get().asFile.absolutePath, layout.projectDirectory.dir("src/test/resources/fixtures").asFile.absolutePath)
    doFirst { delete(layout.buildDirectory.dir("selftest")) }
}
tasks.check { dependsOn("selfTest") }
publishing { publications { create<MavenPublication>("release") { from(components["java"]); artifactId = "livephoto-core" } } }
