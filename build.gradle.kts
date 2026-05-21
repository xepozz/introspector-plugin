import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("com.google.devtools.ksp")
}

dependencies {
    // kotlinx-serialization-json is bundled with the IDE (and with the com.intellij.mcpServer
    // plugin). We must use the platform copy at runtime — bundling our own creates two
    // independent KSerializer classloaders, which makes serializerOrNull(KType) return null
    // for our @Serializable data classes when MCP's reflection-based bridge looks them up,
    // and the bridge then throws "Result type X is not serializable".
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Phase 2: Kotlin runtime execution via JSR-223 ScriptEngine.
    // kotlin-scripting-jsr223 pulls kotlin-compiler-embeddable transitively, which gives us
    // a self-contained compiler inside the plugin's classloader, isolated from the IDE's
    // bundled Kotlin plugin.
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.1.20")

    // docs/MCP_TOOLS.md generator — runs as part of compileKotlin; see doc-processor/.
    ksp(project(":doc-processor"))

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.jetbrains.kotlin")
        // com.intellij.java ships the Java PSI (PsiClass, JavaPsiFacade, ClassFileDecompilers).
        // Required at compile time for code.* tools; at runtime they only load when the IDE
        // includes the Java module — gated via META-INF/java-introspect.xml's optional depends.
        bundledPlugin("com.intellij.java")
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

// Tell the KSP processor where to write the markdown reference. KSP runs as part of
// compileKotlin so every `./gradlew build` (and `./gradlew buildPlugin`) refreshes the file.
ksp {
    arg("docOutput", layout.projectDirectory.file("docs/MCP_TOOLS.md").asFile.absolutePath)
}
