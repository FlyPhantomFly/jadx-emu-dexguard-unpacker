import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
    application
}

version = System.getenv("VERSION") ?: "1.0.6"

repositories {
    mavenCentral()
    maven("https://jitpack.io")   // unidbg is published here
}

dependencies {
    // engine + jadx (provided by the host at runtime)
    compileOnly("io.github.nitanmarcel:jadx-emu:0.1.0-beta.4")
    compileOnly("io.github.skylot:jadx-core:1.5.6")
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    // the core extension's public SPI (NativeBackendProvider). Build the core jar first: (cd ..; ./gradlew jar).
    // Version-agnostic so a bumped core jar (dexguard-unpacker-<ver>.jar) is picked up automatically.
    compileOnly(fileTree("../build/libs") { include("dexguard-unpacker-*.jar"); exclude("*native-unidbg*") })

    // NEEDS-VERIFICATION: confirm the current unidbg artifact id/version. unidbg-android pulls a
    // CPU backend (unicorn) with host-native libraries; works on x86-64 hosts (e.g. Parrot).
    implementation("com.github.zhkl0228:unidbg-android:0.9.8")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }
tasks.jar {
    archiveBaseName = "dexguard-unpacker-native-unidbg"
    // Bundle unidbg + its dependencies. The host loads this as a plain extension jar and does NOT have
    // unidbg on its classpath: a thin jar (~15 KB) fails with NoClassDefFoundError the moment it is used.
    // NEEDS-VERIFICATION: merged META-INF/services files are first-wins; confirm unidbg still finds its backends.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}


application {
    mainClass.set("jadx.emu.ext.dexguard.unidbg.StandaloneKt")
}
