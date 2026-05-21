package com.github.xepozz.introspectorplugin.core

import com.github.xepozz.introspectorplugin.model.ClassLocation
import com.github.xepozz.introspectorplugin.model.ClassMetadata
import com.github.xepozz.introspectorplugin.model.ClassSourceState
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.libraries.LibraryUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiFile
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.search.GlobalSearchScope

/**
 * Resolves a fully-qualified class name within a project to its source representation, mirroring
 * what the user sees when they Ctrl-click the symbol in the editor:
 *
 *   SOURCE           — module source root (.java / .kt)
 *   ATTACHED_SOURCE  — a library .class that has a sources jar attached
 *   DECOMPILED       — a library .class without sources, but the bundled Fernflower decompiler
 *                      can render method bodies
 *   STUBS_ONLY       — a library .class without sources AND without a Light decompiler — only
 *                      signatures are available
 *   NOT_FOUND        — the FQN is unknown in the project's global scope
 *
 * Callers must invoke from inside a read action (see [com.github.xepozz.introspectorplugin.util.readActionBlocking]).
 */
object ClassSourceResolver {

    data class Resolution(
        val state: ClassSourceState,
        val psiClass: PsiClass?,
        /** PSI file whose [PsiFile.getText] should be returned to the user — already
         *  navigated to attached sources when applicable. */
        val textFile: PsiFile?,
        /** Backing VirtualFile of [textFile]. */
        val textVFile: VirtualFile?,
        /** Original .class VFS file when [psiClass] is compiled; null for SOURCE/NOT_FOUND. */
        val classVFile: VirtualFile?,
        /** FQCN of the decompiler that backs [textFile], when state is DECOMPILED. */
        val decompilerClass: String? = null,
    )

    fun resolve(project: Project, fqn: String): Resolution {
        val facade = JavaPsiFacade.getInstance(project)
        // findClass returns the first match; that's what IDE navigation uses too.
        val cls = facade.findClass(fqn, GlobalSearchScope.allScope(project))
            ?: return Resolution(ClassSourceState.NOT_FOUND, null, null, null, null)

        val containing = cls.containingFile
        val containingVFile = containing?.virtualFile

        // Module / source code path — not a compiled element.
        if (cls !is PsiCompiledElement) {
            return Resolution(ClassSourceState.SOURCE, cls, containing, containingVFile, null)
        }

        // From here on it's a .class. Navigation may point at attached sources.
        val nav = cls.navigationElement
        if (nav !== cls && nav !is PsiCompiledElement) {
            val navFile = nav.containingFile
            return Resolution(
                state = ClassSourceState.ATTACHED_SOURCE,
                psiClass = cls,
                textFile = navFile,
                textVFile = navFile?.virtualFile,
                classVFile = containingVFile,
            )
        }

        // Compiled, no attached sources — decide between Decompiled and StubsOnly by probing
        // the registered ClassFileDecompilers extension point. ClsFileImpl's own getText()
        // would silently fall back to stubs if no Light is registered, so we ask directly.
        val classV = containingVFile
        val decompiler = classV?.let {
            ClassFileDecompilers.getInstance().find(it, ClassFileDecompilers.Light::class.java)
        }
        return if (decompiler != null) {
            Resolution(
                state = ClassSourceState.DECOMPILED,
                psiClass = cls,
                textFile = containing,
                textVFile = containingVFile,
                classVFile = containingVFile,
                decompilerClass = decompiler.javaClass.name,
            )
        } else {
            Resolution(
                state = ClassSourceState.STUBS_ONLY,
                psiClass = cls,
                textFile = containing,
                textVFile = containingVFile,
                classVFile = containingVFile,
            )
        }
    }

    fun metadataOf(cls: PsiClass): ClassMetadata {
        val kind = when {
            cls.isAnnotationType -> "annotation"
            cls.isEnum -> "enum"
            cls.isInterface -> "interface"
            cls.isRecord -> "record"
            else -> "class"
        }
        val modList = cls.modifierList
        val modifiers = if (modList == null) emptyList()
        else listOf(
            "public", "protected", "private", "static", "abstract", "final",
            "sealed", "non-sealed", "default",
        ).filter { modList.hasModifierProperty(it) }
        return ClassMetadata(
            fqn = cls.qualifiedName ?: cls.name ?: "<anonymous>",
            simpleName = cls.name ?: "<anonymous>",
            kind = kind,
            modifiers = modifiers,
            superClass = cls.superClass?.qualifiedName,
            interfaces = cls.interfaces.mapNotNull { it.qualifiedName },
            typeParameters = cls.typeParameters.map { it.name ?: "?" },
            containingClass = cls.containingClass?.qualifiedName,
            methodCount = cls.methods.size,
            fieldCount = cls.fields.size,
            innerClassCount = cls.innerClasses.size,
        )
    }

    fun locationOf(project: Project, res: Resolution): ClassLocation {
        val vFile = res.textVFile ?: res.classVFile
        val ext = vFile?.extension
        val url = vFile?.url
        val (jarUrl, ownerName) = ownerInfo(project, vFile)
        return ClassLocation(extension = ext, fileUrl = url, jarUrl = jarUrl, ownerName = ownerName)
    }

    /** Returns (jarRootUrl, ownerName) — jar url when the file lives in a jar; module/library
     *  name otherwise. */
    fun ownerInfo(project: Project, vFile: VirtualFile?): Pair<String?, String?> {
        if (vFile == null) return null to null
        val jarRoot = VfsUtilCore.getRootFile(vFile)?.takeIf { it.fileSystem.protocol == "jar" }
        val index = ProjectFileIndex.getInstance(project)
        val module = ModuleUtilCore.findModuleForFile(vFile, project)
        if (module != null) {
            return jarRoot?.url to module.name
        }
        val orderEntries: List<OrderEntry> = index.getOrderEntriesForFile(vFile)
        val libName = orderEntries.asSequence()
            .map { it.presentableName }
            .firstOrNull()
            ?: LibraryUtil.findLibraryEntry(vFile, project)?.presentableName
        return jarRoot?.url to libName
    }
}
