import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("com.google.devtools.ksp")
    // Kover instead of JaCoCo: the IntelliJ Platform Gradle plugin runs tests under
    // com.intellij.util.lang.PathClassLoader, which JaCoCo's on-the-fly instrumentation
    // can't see — every counter ends up 0/N. Kover (also a JetBrains project) handles
    // that classloader natively and emits a JaCoCo-compatible XML alongside its own HTML.
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            includes {
                classes("com.github.xepozz.ide.introspector.*")
            }
            // Generated / IDE-only surfaces — keep them out so the headline number reflects
            // logic we actually own and can test off-IDE.
            excludes {
                classes(
                    "com.github.xepozz.ide.introspector.toolwindow.*",   // Swing tool window
                    "com.github.xepozz.ide.introspector.tools.*",        // MCP toolset entry points
                    "com.github.xepozz.ide.introspector.exec.*",         // Kotlin runtime execution
                    "com.github.xepozz.ide.introspector.model.*",        // @Serializable data classes
                    "com.github.xepozz.ide.introspector.core.ClassSourceResolver*", // Java PSI heavy
                    "*\$\$serializer",                                      // kotlinx.serialization synthetics
                    "*Companion",                                           // boilerplate
                )
            }
        }
    }
}

dependencies {
    // kotlinx-serialization-json is bundled with the IDE (and with the com.intellij.mcpServer
    // plugin). We must use the platform copy at runtime — bundling our own creates two
    // independent KSerializer classloaders, which makes serializerOrNull(KType) return null
    // for our @Serializable data classes when MCP's reflection-based bridge looks them up,
    // and the bridge then throws "Result type X is not serializable".
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Phase 2: Kotlin runtime execution via JSR-223 ScriptEngine.
    // kotlin-scripting-jsr223 pulls kotlin-compiler-embeddable transitively, which gives us
    // a self-contained compiler inside the plugin's classloader, isolated from the IDE's
    // bundled Kotlin plugin.
    //
    // `runtimeOnly` (NOT `implementation`) is critical for two reasons:
    //   1. The code uses only javax.script.* (standard JDK) — kotlin-scripting-jsr223 is
    //      discovered through META-INF/services at runtime, so we never need it at compile.
    //   2. kotlin-compiler-embeddable bundles its OWN copy of IntelliJ platform resources
    //      (kotlinx-coroutines 1.8.x, an older `messages/JavaPsiBundle.properties`, etc.).
    //      If those land on testRuntimeClasspath, they shadow the real IDE's copies and
    //      every BasePlatformTestCase setUp dies at NoSuchMethodError / missing-resource.
    //      Marking it `runtimeOnly` keeps it on the production plugin jar but OFF the test
    //      classpath — so the IDE-provided coroutines and resources win in tests.
    runtimeOnly("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.1.20") {
        // Same shadowing problem: scripting transitively brings upstream coroutines 1.8.x
        // which override the IDE's JetBrains-patched build, breaking the test JVM at
        // `ContextKt.<clinit>` (NoSuchMethodError on limitedParallelism / runBlockingWith…).
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    // docs/MCP_TOOLS.md generator — runs as part of compileKotlin; see doc-processor/.
    ksp(project(":doc-processor"))

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        plugin("com.intellij.mcpServer", "252.28238.29")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

// `runtimeOnly` puts the deps on production runtime AND testRuntimeClasspath (the latter
// extends the former). kotlin-compiler-embeddable bundles an older
// `messages/JavaPsiBundle.properties` (and other IntelliJ platform resources) that shadow
// the IDE's modern ones during tests, triggering missing-resource errors during the
// platform's FileTypeManager preload. Removing the scripting stack from test runtime is
// safe because tests don't exec Kotlin scripts.
configurations.testRuntimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-jsr223")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-compiler-embeddable")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-compiler-impl-embeddable")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-script-runtime")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-daemon-embeddable")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("252")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Tests should be deterministic across machines: enforce headless mode so a misconfigured
// local display doesn't change behaviour. Window-dependent tests use Assume.assumeFalse(
// GraphicsEnvironment.isHeadless()) and skip cleanly when this is set.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

// Tell the KSP processor where to write the markdown reference. KSP runs as part of
// compileKotlin so every `./gradlew build` (and `./gradlew buildPlugin`) refreshes the file.
ksp {
    arg("docOutput", layout.projectDirectory.file("docs/MCP_TOOLS.md").asFile.absolutePath)
}
