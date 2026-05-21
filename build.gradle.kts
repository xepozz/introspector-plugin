import org.jetbrains.intellij.platform.gradle.TestFrameworkType

buildscript {
    dependencies {
        // ASM for the bytecode-walking doc generator below. Pinning here keeps it off
        // the production plugin classpath — only the Gradle script process sees it.
        classpath("org.ow2.asm:asm:9.7")
        classpath("org.ow2.asm:asm-tree:9.7")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// Emit MethodParameters into bytecode so the generator can read real parameter names
// instead of arg0/arg1. Cheap (~tens of bytes per method); not a runtime cost.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        javaParameters.set(true)
    }
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

// -------------------------------------------------------------------------------------
// docs/MCP_TOOLS.md generator.
//
// Single source of truth is the @McpTool / @McpDescription annotations on the
// McpToolset classes. This task walks the compiled .class files with ASM and reads:
//   - RuntimeVisibleAnnotations on each method   (@McpTool, @McpDescription)
//   - RuntimeVisibleParameterAnnotations         (@McpDescription on parameters)
//   - MethodParameters attribute                 (parameter names — enabled above
//                                                  via javaParameters=true)
//   - The method descriptor / signature          (return type + parameter types)
//
// Why bytecode and not regex over .kt files? It's exactly the same metadata MCP server's
// own ReflectionToolsProvider reads at runtime — so if a tool builds, this task sees the
// same shape MCP clients will see. Robust to source-level formatting (multi-line strings,
// comments inside annotations, custom default expressions, …).
// -------------------------------------------------------------------------------------
tasks.register("generateToolsDoc") {
    group = "documentation"
    description = "Reads @McpTool / @McpDescription annotations from compiled bytecode and writes docs/MCP_TOOLS.md."

    // Run after our Kotlin is compiled so the .class files exist.
    dependsOn("compileKotlin")

    val toolsClassesDir = layout.buildDirectory.dir("classes/kotlin/main/com/github/xepozz/introspectorplugin/tools")
    val toolsDocFile = layout.projectDirectory.file("docs/MCP_TOOLS.md")

    inputs.dir(toolsClassesDir).withPropertyName("toolsClassesDir")
    outputs.file(toolsDocFile).withPropertyName("toolsDocFile")

    doLast {
        // All logic inline (no top-level helpers, no local data classes — Kotlin Gradle DSL
        // IR backend rejects them) so Gradle's configuration cache can serialize the task.
        // Tools represented as Map<String, Any> with keys:
        //   "class"     (String, simple class name of the McpToolset)
        //   "name"      (String, e.g. "ui.get_tree")
        //   "description" (String)
        //   "params"    (List<Triple<paramName, kotlinType, paramDescription>>)
        //   "returnType" (String)

        val toolsetIfaceInternal = "com/intellij/mcpserver/McpToolset"
        val mcpToolDesc = "Lcom/intellij/mcpserver/annotations/McpTool;"
        val mcpDescDesc = "Lcom/intellij/mcpserver/annotations/McpDescription;"

        // AnnotationNode.values is List<Any> shaped as [key1, value1, key2, value2, ...].
        // This is ASM's runtime representation; pulling a single attribute is a linear scan.
        fun pullAnnAttr(values: List<Any?>?, key: String): String? {
            if (values == null) return null
            var i = 0
            while (i < values.size - 1) {
                if (values[i] == key) return values[i + 1] as? String
                i += 2
            }
            return null
        }

        // Render a JVM type descriptor or a generic signature segment as a Kotlin-ish
        // type name: java/lang/String → String, kotlinx/serialization/json/JsonElement →
        // JsonElement, primitives → Int/Long/Boolean/etc., Kotlin's Continuation removed.
        fun jvmInternalToReadable(internal: String): String = when (internal) {
            "java/lang/String" -> "String"
            "java/lang/Object" -> "Any"
            "java/lang/Integer" -> "Int"
            "java/lang/Long" -> "Long"
            "java/lang/Boolean" -> "Boolean"
            "java/lang/Double" -> "Double"
            "java/lang/Float" -> "Float"
            "java/util/List" -> "List"
            "java/util/Map" -> "Map"
            "kotlin/Unit" -> "Unit"
            "kotlin/jvm/functions/Function0" -> "Function0"
            else -> internal.substringAfterLast('/')
        }

        // Parse a single JVM type descriptor (T or L...; or [T) at offset; returns
        // (rendered, charsConsumed). Doesn't handle generic <...> — that comes from
        // signature parsing below.
        fun parseJvmType(d: String, off: Int): Pair<String, Int> {
            return when (val c = d[off]) {
                'V' -> "Unit" to 1
                'I' -> "Int" to 1
                'J' -> "Long" to 1
                'Z' -> "Boolean" to 1
                'B' -> "Byte" to 1
                'S' -> "Short" to 1
                'C' -> "Char" to 1
                'F' -> "Float" to 1
                'D' -> "Double" to 1
                '[' -> {
                    val (inner, used) = parseJvmType(d, off + 1)
                    "${inner}Array" to used + 1
                }
                'L' -> {
                    val end = d.indexOf(';', off)
                    val internal = d.substring(off + 1, end)
                    jvmInternalToReadable(internal) to (end - off + 1)
                }
                else -> "?" to 1
            }
        }

        // Parse a Java method descriptor like "(Ljava/lang/String;I)Lkotlin/Unit;" into
        // (paramTypes, returnType). For suspend functions Kotlin appends a Continuation
        // parameter at the end — we strip it and treat the Continuation's type argument as
        // the real return.
        fun parseDescriptor(desc: String, signature: String?): Pair<List<String>, String> {
            // Generic signature is much richer but harder to parse. Use it only for the
            // suspend-return extraction; for everything else stick with the descriptor.
            val params = mutableListOf<String>()
            var i = 1  // skip leading (
            while (i < desc.length && desc[i] != ')') {
                val (rendered, used) = parseJvmType(desc, i)
                params.add(rendered)
                i += used
            }
            val rawReturn = parseJvmType(desc, i + 1).first
            // Suspend funs: the last param is Continuation<? super T>; pull T out of the
            // generic signature if available, else fall back to "Any" rather than show
            // Continuation in the doc.
            val isSuspend = params.lastOrNull() == "Continuation"
            if (isSuspend) {
                params.removeAt(params.size - 1)
                val real = signature?.let {
                    // Quick & dirty: find Continuation<-Lfoo/Bar;>;)
                    val marker = "Continuation<-L"
                    val idx = it.indexOf(marker)
                    if (idx < 0) null else {
                        val start = idx + marker.length
                        val end = it.indexOf(';', start)
                        if (end < 0) null else jvmInternalToReadable(it.substring(start, end))
                    }
                } ?: "Any"
                return params to real
            }
            return params to rawReturn
        }

        val tools = mutableListOf<Map<String, Any>>()

        toolsClassesDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                val cn = org.objectweb.asm.tree.ClassNode()
                org.objectweb.asm.ClassReader(classFile.readBytes()).accept(cn, 0)
                if (cn.interfaces?.contains(toolsetIfaceInternal) != true) return@forEach

                val toolsetSimpleName = cn.name.substringAfterLast('/')

                for (method in cn.methods.orEmpty()) {
                    val anns = method.visibleAnnotations.orEmpty()
                    val toolAnn = anns.firstOrNull { it.desc == mcpToolDesc } ?: continue
                    val descAnn = anns.firstOrNull { it.desc == mcpDescDesc }

                    val explicitName = pullAnnAttr(toolAnn.values, "name")
                    val toolName = if (explicitName.isNullOrEmpty()) method.name else explicitName
                    val description = pullAnnAttr(descAnn?.values, "description")
                        ?.trimMargin()?.trim().orEmpty()

                    val (paramTypes, returnType) = parseDescriptor(method.desc, method.signature)

                    // Parameter names from MethodParameters attribute (KotlinCompile
                    // configured with javaParameters=true above).
                    val paramNames = method.parameters?.map { it.name } ?: List(paramTypes.size) { "arg$it" }
                    val paramAnnLists = method.visibleParameterAnnotations
                        ?: arrayOfNulls<List<org.objectweb.asm.tree.AnnotationNode>>(paramTypes.size)

                    val params = paramTypes.mapIndexedNotNull { i, type ->
                        val name = paramNames.getOrNull(i) ?: "arg$i"
                        val paramDescAnn = paramAnnLists.getOrNull(i)
                            ?.firstOrNull { it.desc == mcpDescDesc }
                        val paramDesc = pullAnnAttr(paramDescAnn?.values, "description")
                            ?.trimMargin()?.trim().orEmpty()
                        Triple(name, type, paramDesc)
                    }

                    tools += mapOf(
                        "class" to toolsetSimpleName,
                        "name" to toolName,
                        "description" to description,
                        "params" to params,
                        "returnType" to returnType,
                    )
                }
            }
        tools.sortBy { it["name"] as String }

        fun anchor(toolName: String) = toolName.lowercase().replace('.', '-').replace(Regex("[^a-z0-9-]"), "")

        val md = buildString {
            appendLine("<!-- AUTO-GENERATED by ./gradlew generateToolsDoc. Do not edit by hand — change the source-level @McpDescription annotations instead. -->")
            appendLine()
            appendLine("# IDE Introspect MCP — Tool Reference")
            appendLine()
            appendLine("Generated from the `@McpTool` / `@McpDescription` annotations on the `McpToolset`")
            appendLine("classes under `src/main/kotlin/com/github/xepozz/introspectorplugin/tools/`.")
            appendLine("Re-run `./gradlew generateToolsDoc` (or any `./gradlew build`) to refresh.")
            appendLine()
            appendLine("**Total tools:** ${tools.size}")
            appendLine()
            appendLine("## Tools by group")
            appendLine()
            val grouped = tools.groupBy { (it["name"] as String).substringBefore('.', missingDelimiterValue = "other") }
            for ((group, list) in grouped.entries.sortedBy { it.key }) {
                appendLine("### `$group.*` (${list.size})")
                appendLine()
                for (t in list) {
                    val n = t["name"] as String
                    appendLine("- [`$n`](#${anchor(n)})")
                }
                appendLine()
            }
            appendLine("---")
            appendLine()
            for (t in tools) {
                val n = t["name"] as String
                appendLine("## `$n`")
                appendLine()
                appendLine("*${t["class"]}*")
                appendLine()
                appendLine(t["description"] as String)
                appendLine()
                @Suppress("UNCHECKED_CAST")
                val params = t["params"] as List<Triple<String, String, String>>
                if (params.isNotEmpty()) {
                    appendLine("**Parameters**")
                    appendLine()
                    appendLine("| Name | Type | Description |")
                    appendLine("| --- | --- | --- |")
                    for ((pn, pt, pd) in params) {
                        val escDesc = pd.replace("|", "\\|").replace("\n", " ")
                        appendLine("| `$pn` | `${pt.replace("|", "\\|")}` | $escDesc |")
                    }
                    appendLine()
                }
                appendLine("**Returns:** `${t["returnType"]}`")
                appendLine()
                appendLine("---")
                appendLine()
            }
        }

        val target = toolsDocFile.asFile
        target.parentFile.mkdirs()
        target.writeText(md)
        logger.lifecycle("Wrote ${tools.size} MCP tool descriptions to ${target.relativeTo(target.parentFile.parentFile)}")
    }
}

// Wire the generator into both the standard `build` task and `buildPlugin` (the one that
// produces the distributable zip, which is what most users actually invoke). Listing them
// separately keeps the dependency explicit — relying on the transitive `buildPlugin → build`
// path isn't reliable across IntelliJ Platform Gradle Plugin versions.
listOf("build", "buildPlugin").forEach { taskName ->
    tasks.named(taskName) { dependsOn("generateToolsDoc") }
}
