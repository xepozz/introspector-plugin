package com.github.xepozz.ide.introspector.docs

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import java.io.File

/**
 * KSP processor that discovers all functions annotated with `@McpTool` (which all live on
 * classes implementing `McpToolset`), reads the matching `@McpDescription` annotations on
 * the method itself and on every parameter, then renders a markdown reference.
 *
 * Output path is taken from the KSP option `docOutput` (set in the consumer's build.gradle.kts
 * via `ksp { arg("docOutput", "...") }`). Falling back to `<project>/docs/MCP_TOOLS.md` if
 * the option is missing.
 */
class McpDocProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    private companion object {
        const val MCP_TOOL = "com.intellij.mcpserver.annotations.McpTool"
        const val MCP_DESC = "com.intellij.mcpserver.annotations.McpDescription"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotated = resolver.getSymbolsWithAnnotation(MCP_TOOL).toList()
        val toolFunctions = annotated.filterIsInstance<KSFunctionDeclaration>()
        if (toolFunctions.isEmpty()) {
            // No tools defined in this round — nothing to render. (Don't emit an empty doc,
            // we'd otherwise overwrite a freshly populated one when KSP runs in multiple
            // rounds.)
            return emptyList()
        }

        val tools = toolFunctions.map(::extractTool).sortedBy { it.name }
        val md = renderMarkdown(tools)

        val outputPath = env.options["docOutput"]
            ?: error("KSP option 'docOutput' is required (set via ksp { arg(\"docOutput\", \"...\") }).")
        val out = File(outputPath)
        out.parentFile?.mkdirs()
        out.writeText(md)
        env.logger.info("McpDocProcessor: wrote ${tools.size} tools to $outputPath")
        return emptyList()
    }

    // ---------- extraction ----------

    private data class ToolInfo(
        val toolsetClass: String,
        val name: String,
        val description: String,
        val parameters: List<ParamInfo>,
        val returnType: String,
    )

    private data class ParamInfo(val name: String, val type: String, val description: String)

    private fun extractTool(fn: KSFunctionDeclaration): ToolInfo {
        val toolAnn = fn.annotations.first { isAnnotation(it, MCP_TOOL) }
        val descAnn = fn.annotations.firstOrNull { isAnnotation(it, MCP_DESC) }

        val explicitName = (toolAnn.arguments
            .firstOrNull { it.name?.asString() == "name" }
            ?.value as? String)?.takeIf { it.isNotBlank() }
        val toolName = explicitName ?: fn.simpleName.asString()

        val rawDescription = descAnn?.arguments
            ?.firstOrNull { it.name?.asString() == "description" }
            ?.value as? String
        val description = rawDescription?.trimMargin()?.trim().orEmpty()

        val parameters = fn.parameters.map(::extractParam)
        val returnType = renderType(fn.returnType?.resolve())
        val parentClass = (fn.parentDeclaration as? KSClassDeclaration)?.simpleName?.asString().orEmpty()

        return ToolInfo(parentClass, toolName, description, parameters, returnType)
    }

    private fun extractParam(p: KSValueParameter): ParamInfo {
        val descAnn = p.annotations.firstOrNull { isAnnotation(it, MCP_DESC) }
        val raw = descAnn?.arguments
            ?.firstOrNull { it.name?.asString() == "description" }
            ?.value as? String
        return ParamInfo(
            name = p.name?.asString() ?: "<unnamed>",
            type = renderType(p.type.resolve()),
            description = raw?.trimMargin()?.trim().orEmpty(),
        )
    }

    private fun isAnnotation(ann: KSAnnotation, fqn: String): Boolean {
        // shortName check first (cheap), then resolve only on match — keeps the hot path fast.
        val short = fqn.substringAfterLast('.')
        if (ann.shortName.asString() != short) return false
        val resolved = ann.annotationType.resolve().declaration.qualifiedName?.asString() ?: return false
        return resolved == fqn
    }

    /** Renders a KSType in Kotlin-source style: short class name + generic args + ? for nullable. */
    private fun renderType(t: KSType?): String {
        if (t == null) return "?"
        val name = t.declaration.simpleName.asString()
        val args = t.arguments
        val rendered = if (args.isEmpty()) name
        else "$name<${args.joinToString(", ") { renderType(it.type?.resolve()) }}>"
        return if (t.isMarkedNullable) "$rendered?" else rendered
    }

    // ---------- rendering ----------

    private fun renderMarkdown(tools: List<ToolInfo>): String = buildString {
        appendLine("<!-- AUTO-GENERATED by the KSP McpDocProcessor on every build. Do not edit by hand — change the source-level @McpTool / @McpDescription annotations instead. -->")
        appendLine()
        appendLine("# IDE Introspector — Tool Reference")
        appendLine()
        appendLine("Generated from the `@McpTool` / `@McpDescription` annotations on the `McpToolset`")
        appendLine("classes by a KSP processor (`doc-processor/`) that runs as part of `compileKotlin`.")
        appendLine("To refresh: any `./gradlew build` (or `./gradlew compileKotlin`) regenerates this file.")
        appendLine()
        appendLine("**Total tools:** ${tools.size}")
        appendLine()
        appendLine("## Tools by group")
        appendLine()
        val grouped = tools.groupBy { it.name.substringBefore('.', missingDelimiterValue = "other") }
        for ((group, list) in grouped.entries.sortedBy { it.key }) {
            appendLine("### `$group.*` (${list.size})")
            appendLine()
            for (t in list) appendLine("- [`${t.name}`](#${anchor(t.name)})")
            appendLine()
        }
        appendLine("---")
        appendLine()
        for (t in tools) {
            appendLine("## `${t.name}`")
            appendLine()
            appendLine("*${t.toolsetClass}*")
            appendLine()
            appendLine(t.description)
            appendLine()
            if (t.parameters.isNotEmpty()) {
                appendLine("**Parameters**")
                appendLine()
                appendLine("| Name | Type | Description |")
                appendLine("| --- | --- | --- |")
                for (p in t.parameters) {
                    val desc = p.description.replace("|", "\\|").replace("\n", " ")
                    appendLine("| `${p.name}` | `${p.type.replace("|", "\\|")}` | $desc |")
                }
                appendLine()
            }
            appendLine("**Returns:** `${t.returnType}`")
            appendLine()
            appendLine("---")
            appendLine()
        }
    }

    private fun anchor(toolName: String): String =
        toolName.lowercase().replace('.', '-').replace(Regex("[^a-z0-9-]"), "")
}

class McpDocProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        _root_ide_package_.com.github.xepozz.ide.introspector.docs.McpDocProcessor(environment)
}
