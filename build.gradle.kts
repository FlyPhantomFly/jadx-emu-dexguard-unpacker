import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
}

version = System.getenv("VERSION") ?: "2.2.0"

// Built against io.github.flyphantomfly:jadx-emu, resolved from ~/.m2 (scripts/deploy-engine.sh publishes it there).
// Set EMU_VERSION if the engine you published has a different version.
val emuVersion = System.getenv("EMU_VERSION") ?: "0.2.0"

repositories {
    mavenLocal()
    mavenCentral()
    google()
}

dependencies {
    compileOnly("io.github.flyphantomfly:jadx-emu:$emuVersion")

    compileOnly("io.github.skylot:jadx-core:1.5.6")
    compileOnly(kotlin("stdlib"))
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.github.flyphantomfly:jadx-emu:$emuVersion")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    // End-to-end tests assemble smali to a dex and run it through the real interpreter. Same artifacts and versions
    // jadx-emu's own test suite uses.
    testImplementation("io.github.skylot:jadx-core:1.5.6")
    testImplementation("io.github.skylot:jadx-dex-input:1.5.6")
    testImplementation("com.android.tools.smali:smali:3.0.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName = "dexguard-unpacker"
}
