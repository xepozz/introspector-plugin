package com.github.xepozz.ide.introspector.model

import kotlinx.serialization.Serializable

/**
 * Response models for `arch.check_lock_requirements` and `arch.check_threading_requirements`.
 *
 * Both tools share the exact same response shape — only the annotation set under analysis
 * (and therefore the values that appear in `expected[]` / `callerAnnotations[]`) differs.
 * See `docs/plans/arch-devkit-mirror.md` for the design rationale.
 */

/**
 * The six IntelliJ `com.intellij.util.concurrency.annotations.*` annotations our analyzer
 * understands. We hard-code the FQNs (see [RequirementAnnotation.fqn]) rather than depending
 * on the DevKit plugin's `RequiresReadLockAnnotationProvider` — the FQNs have been stable
 * since 2019, and pulling DevKit in would defeat the whole point of mirroring its tools for
 * IDEs that ship without it.
 */
@Serializable
enum class RequirementKind {
    READ_LOCK,
    WRITE_LOCK,
    NO_READ_LOCK,
    EDT,
    BGT,
    BLOCKING_CONTEXT,
}

@Serializable
data class RequirementAnnotation(
    val kind: RequirementKind,
    val fqn: String,
)

/**
 * One caller of the target method, with the analyzer's verdict on whether it statically
 * satisfies the target's contract.
 *
 * `status` values:
 *  - "ok"        — caller carries the same annotation (or a stronger one — e.g. write lock
 *                  subsumes read lock), or is lexically inside a recognised wrapper
 *                  (ReadAction.run / invokeLater / executeOnPooledThread / …).
 *  - "mismatch"  — caller has no compatible annotation AND is not inside a recognised wrapper.
 *  - "unknown"   — the call site is a lambda passed to an opaque dispatcher (Runnable
 *                  consumers, Future, ExecutorService, kotlinx coroutine builders) that we
 *                  can't reason about statically. Agent should escalate to manual review.
 *
 * `contextHints` enumerates the wrappers / annotations we matched against — e.g.
 * `["inside-ReadAction.run"]`, `["@RequiresReadLock"]`, `["inside-invokeLater"]`.
 */
@Serializable
data class CallSiteAnalysis(
    val fileUrl: String,
    val range: TextRangeInfo,
    val callerSignature: String,
    val callerAnnotations: List<RequirementAnnotation>,
    val contextHints: List<String>,
    val status: String,
    val reason: String,
)

@Serializable
data class CheckRequirementsResponse(
    /** The target method we resolved + analysed. */
    val target: TargetInfo,
    /** The annotation(s) on the target that drive the contract. Empty → no analysis. */
    val expected: List<RequirementAnnotation>,
    val callSites: List<CallSiteAnalysis>,
    val total: Int,
    val truncated: Boolean = false,
)
