import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Phase 2: Kotlin runtime execution via JSR-223 ScriptEngine.
    // kotlin-scripting-jsr223 pulls kotlin-compiler-embeddable transitively, which gives us
    // a self-contained compiler inside the plugin's classloader, isolated from the IDE's
    // bundled Kotlin plugin.
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.1.20")

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.jetbrains.kotlin")
        plugin("com.intellij.mcpServer", "252.28238.29")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("252")
            untilBuild.set("253.*")
        }
    }
}

kotlin {
    jvmToolchain(21)
}
