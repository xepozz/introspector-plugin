package com.github.xepozz.ide.introspector.exec

/**
 * Wraps user-supplied Kotlin into a class that exposes a single `run(project, pluginDisposable)`
 * entry point. The wrapper provides three implicit helpers:
 *
 *   read { ... }   — ReadAction.compute
 *   write { ... }  — WriteCommandAction.runWriteCommandAction
 *   onEdt { ... }  — ApplicationManager.invokeAndWait
 *
 * User code's last expression is wrapped in `run { ... }` so we can capture its return value.
 */
object CodeWrapper {

    const val GENERATED_PACKAGE = "com.github.xepozz.ide.introspector.exec.generated"
    const val GENERATED_CLASS = "Plugin"

    fun wrap(userCode: String): String = """
        package $GENERATED_PACKAGE

        import com.intellij.openapi.project.Project
        import com.intellij.openapi.Disposable
        import com.intellij.openapi.application.ReadAction
        import com.intellij.openapi.application.ApplicationManager
        import com.intellij.openapi.command.WriteCommandAction
        import com.intellij.openapi.util.Computable

        class $GENERATED_CLASS {
            fun run(project: Project?, pluginDisposable: Disposable): Any? {
                fun <T> read(block: () -> T): T = ReadAction.compute<T, Exception>(block)
                fun <T> write(block: () -> T): T =
                    WriteCommandAction.runWriteCommandAction<T>(project, Computable(block))
                fun <T> onEdt(block: () -> T): T {
                    var result: T? = null
                    ApplicationManager.getApplication().invokeAndWait { result = block() }
                    @Suppress("UNCHECKED_CAST") return result as T
                }
                return kotlin.run {
                    $userCode
                }
            }
        }
    """.trimIndent()
}
