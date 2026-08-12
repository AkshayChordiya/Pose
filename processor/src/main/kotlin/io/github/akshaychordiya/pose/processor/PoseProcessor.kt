package io.github.akshaychordiya.pose.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Visibility

/**
 * The `@Pose` processor. Drives two symbol streams:
 *
 * 1. **Explicit** — every function carrying `@Pose(...)`. Its arguments (name,
 *    wrapInTheme, previews, providers) feed the planner.
 * 2. **Bulk (opt-in)** — every public top-level `@Composable fun … : Unit` when the
 *    module's `@PoseSetup(generateForAllPublicComposables = true)`, excluding ones
 *    that already carry `@Pose`, carry `@PoseIgnore`, have a handwritten
 *    `@Preview`, are wrapper-shaped, or belong to the config object itself.
 */
public class PoseProcessor(
    private val codeGenerator: CodeGenerator,
    private val options: Options,
    logger: KSPLogger,
    /** Raw KSP args, kept so unknown `pose.*` keys can be flagged (PG023). */
    private val rawOptions: Map<String, String> = emptyMap(),
) : SymbolProcessor {

    private val diagnostics = Diagnostics(logger, options.strict, options.verboseSkips)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Flag misspelled Gradle keys - `Options.from()` does exact-key lookups, so a
        // typo would otherwise silently take the default and leave no trace.
        rawOptions.keys.filter { it.startsWith("pose.") && it !in Options.KnownKeys }
            .forEach { unknown ->
                val migration = REMOVED_OPTIONS[unknown]
                diagnostics.warn(
                    DiagnosticCode.PG023, node = null,
                    if (migration != null) {
                        "`$unknown` was removed in 0.6.0 and is being ignored — $migration"
                    } else {
                        "`$unknown` is not a Pose option${suggestionFor(unknown)}. " +
                            "Known options: ${Options.KnownKeys.sorted().joinToString()}."
                    },
                )
            }

        // A @PoseSetup object, when present, layers over the KSP options.
        val setup = PoseSetupResolver.resolve(resolver, diagnostics)
        val options = setup?.let {
            this.options.mergedWith(
                Options.PoseSetupOverrides(
                    objectFqn = it.objectFqn,
                    overridesTheme = it.overridesTheme,
                    overridesWrapper = it.overridesWrapper,
                    generateForAllPublicComposables = it.args.generateForAllPublicComposables,
                    provideInspectionMode = it.args.provideInspectionMode,
                    maxPreviewsPerComposable = it.args.maxPreviewsPerComposable,
                    maxDepth = it.args.maxDepth,
                    collectionSize = it.args.collectionSize,
                )
            )
        } ?: this.options
        val setupPreviewFqns = setup?.args?.previewAnnotationFqns.orEmpty()
        // Bulk mode may have arrived via the setup object, after `diagnostics` was
        // built, re-sync so its strict→warning coercion actually applies.
        diagnostics.strict = options.strict

        // `LocalInspectionMode` lives in compose-ui, not compose-runtime. A module
        // could plausibly have the latter without the former, so gate the emission
        // on it actually resolving rather than assuming.
        val inspectionModeAvailable = resolver.getPropertyDeclarationByName(
            resolver.getKSNameFromString(LOCAL_INSPECTION_MODE_FQN),
            includeTopLevel = true,
        ) != null
        if (options.provideInspectionMode && !inspectionModeAvailable) {
            diagnostics.info(
                DiagnosticCode.PG013, node = null,
                "`$LOCAL_INSPECTION_MODE_FQN` not on the compile classpath — " +
                    "generated previews will not provide LocalInspectionMode.",
            )
        }

        val explicitSymbols = resolver.getSymbolsWithAnnotation(POSE_FQN)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()
        val explicitFqns = explicitSymbols.mapNotNull { it.qualifiedName?.asString() }.toSet()

        // The setup object's own Theme/Wrapper are @Composable and would otherwise be
        // picked up by bulk mode previewing a theme renders its empty content lambda.
        val setupObjectFqn = options.setupObjectFqn

        val bulkSymbols = if (options.generatePreviewsForAllPublicComposables) {
            resolver.getSymbolsWithAnnotation(COMPOSABLE_FQN)
                .filterIsInstance<KSFunctionDeclaration>()
                .filter { it.containingFile != null }
                .filter(::looksLikeBulkCandidate)
                .filterNot { fn -> fn.qualifiedName?.asString() in explicitFqns }
                .filterNot { fn -> fn.hasAnnotationByFqn(POSE_IGNORE_FQN) }
                .filterNot { fn -> fn.hasAnnotationByShortName("Preview") }
                // The setup object's own Theme/Wrapper members are @Composable and
                // would otherwise be picked up.
                .filterNot { fn ->
                    setupObjectFqn != null &&
                        (fn.parentDeclaration as? KSClassDeclaration)?.qualifiedName?.asString() == setupObjectFqn
                }
                .toList()
        } else {
            emptyList()
        }

        val planner = PreviewPlanner(resolver = resolver, options = options, diagnostics = diagnostics)
        val emitter = PreviewFileEmitter(
            codeGenerator = codeGenerator,
            options = options,
            inspectionModeAvailable = inspectionModeAvailable,
            setupFile = setup?.declarationFile,
        )

        val plans = mutableListOf<PreviewPlan>()
        for (fn in explicitSymbols) {
            val ann = fn.annotations.firstOrNull { it.annotationTypeFqn() == POSE_FQN } ?: continue
            plans += process(fn, PreviewAnnotationArgs.parse(ann), planner)
        }
        val bulkAnnotationArgs = PreviewAnnotationArgs(
            name = "",
            wrapInTheme = true,
            // A module-wide `@PoseSetup(previews = [...])` applies to bulk-mode
            // composables, which have no annotation of their own to carry it.
            previewAnnotationFqns = setupPreviewFqns,
            providers = emptyList(),
        )
        for (fn in bulkSymbols) {
            plans += process(fn, bulkAnnotationArgs, planner)
        }
        // Generating previews with no config object means no theme — they'll render
        // in Compose's baseline palette, which looks like a Pose bug rather than
        // missing setup. Silence here is what made the 0.6.0 upgrade confusing.
        // Declaring the object without overriding `Theme` is the explicit opt-out.
        if (plans.isNotEmpty() && setup == null) {
            diagnostics.warn(
                DiagnosticCode.PG024, node = null,
                "generated ${plans.size} preview(s) in this module but found no @PoseSetup object, " +
                    "so they render with Compose's default theme rather than yours. Add:\n" +
                    "    @PoseSetup\n" +
                    "    internal object AppPose : PoseConfig {\n" +
                    "        @Composable\n" +
                    "        override fun Theme(content: @Composable () -> Unit) = AppTheme(content)\n" +
                    "    }\n" +
                    "Declare it without overriding `Theme` if unthemed previews are intended. " +
                    "Docs: ${DiagnosticCode.PG024.docsUrl}",
            )
        }

        emitter.emitAll(plans)
        return emptyList()
    }

    private fun process(
        fn: KSFunctionDeclaration,
        annArgs: PreviewAnnotationArgs,
        planner: PreviewPlanner,
    ): List<PreviewPlan> = when (val check = SignatureChecker.check(fn)) {
        is SignatureChecker.Result.Refused -> {
            // `refusal` respects `pose.strict` - hard-errors under strict, warns
            // otherwise. Bulk mode coerces strict to false in `Options.mergedWith`.
            diagnostics.refusal(check.code, fn, check.detail)
            emptyList()
        }
        SignatureChecker.Result.Ok -> planner.plan(fn, annArgs)
    }

    /**
     * Cheap pre-filter to keep bulk mode from spamming warnings on shapes that
     * were never plausible @Pose targets. Deep validation still happens in
     * [SignatureChecker] for surviving candidates.
     */
    private fun looksLikeBulkCandidate(fn: KSFunctionDeclaration): Boolean {
        if (fn.getVisibility() != Visibility.PUBLIC) return false
        val returnFqn = fn.returnType?.resolve()?.declaration?.qualifiedName?.asString()
        if (returnFqn != "kotlin.Unit") return false
        if (fn.typeParameters.isNotEmpty()) return false

        // Wrapper-shaped composables - every required parameter is a @Composable
        // content lambda — render nothing but synthesized empty content, so they're
        // not useful previews. Themes, surfaces and providers all look like this.
        //
        // This used to be covered by matching against `pose.themeFqName`, but a
        // @PoseSetup object invokes the theme inside an override body that KSP
        // can't see, so Pose no longer knows the theme by name. The shape is the
        // only signal left, and it generalises better anyway.
        val required = fn.parameters.filterNot { it.hasDefault }
        if (required.isNotEmpty() && required.all { it.type.resolve().isMarkedComposable() }) return false
        if (fn.functionKind == FunctionKind.TOP_LEVEL) return true
        val parent = fn.parentDeclaration as? KSClassDeclaration ?: return false
        return parent.classKind == ClassKind.OBJECT
    }

    private companion object {
        private const val POSE_FQN = "io.github.akshaychordiya.pose.Pose"
        private const val POSE_IGNORE_FQN = "io.github.akshaychordiya.pose.PoseIgnore"
        private const val COMPOSABLE_FQN = "androidx.compose.runtime.Composable"
        private const val LOCAL_INSPECTION_MODE_FQN = "androidx.compose.ui.platform.LocalInspectionMode"

        /**
         * Options deleted in 0.6.0, with the migration each one needs. Worth naming
         * explicitly: silently ignoring `pose.themeFqName` means previews render
         * unthemed, which looks like a Pose bug rather than a missing config object.
         */
        private val REMOVED_OPTIONS = mapOf(
            "pose.themeFqName" to
                "previews will render UNTHEMED until you add a @PoseSetup object overriding `Theme`. " +
                    "See https://github.com/AkshayChordiya/Pose#install",
            "pose.previewWrapperFqName" to
                "override `Wrapper` on your @PoseSetup object instead.",
            "pose.generatePreviewsForAllPublicComposables" to
                "use @PoseSetup(generateForAllPublicComposables = true).",
            "pose.provideInspectionMode" to "use @PoseSetup(provideInspectionMode = …).",
            "pose.maxPreviewsPerComposable" to "use @PoseSetup(maxPreviewsPerComposable = …).",
            "pose.maxDepth" to "use @PoseSetup(maxDepth = …).",
            "pose.collectionSize" to "use @PoseSetup(collectionSize = …).",
        )

    }
}

/** `" — did you mean `pose.strict`?"`, or empty when nothing is close enough. */
private fun suggestionFor(unknown: String): String {
    val best = Options.KnownKeys.minByOrNull { levenshtein(unknown, it) } ?: return ""
    return if (levenshtein(unknown, best) <= 3) " — did you mean `$best`?" else ""
}

private fun levenshtein(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val curr = IntArray(b.length + 1)
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        prev = curr
    }
    return prev[b.length]
}

private fun KSAnnotation.annotationTypeFqn(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

private fun KSFunctionDeclaration.hasAnnotationByFqn(fqn: String): Boolean =
    annotations.any { it.annotationTypeFqn() == fqn }

private fun KSFunctionDeclaration.hasAnnotationByShortName(shortName: String): Boolean =
    annotations.any { it.shortName.asString() == shortName }
