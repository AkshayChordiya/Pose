package io.github.akshaychordiya.pose.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility

/**
 * The module's `@PoseSetup` object, once found and validated.
 *
 * [objectFqn] is what the emitter calls — `FeaturePose.Theme { … }` — so Pose
 * never needs to know the developer's theme by name.
 */
internal data class PoseSetup(
    val objectFqn: String,
    /**
     * The file the object is declared in. Every generated preview names it as a KSP
     * `Dependencies` source, which is what keeps it in the dirty set on incremental
     * rounds - see [PreviewFileEmitter].
     */
    val declarationFile: KSFile?,
    /** False when [PoseConfig.Theme] wasn't overridden, i.e. the default passthrough. */
    val overridesTheme: Boolean,
    /** False when [PoseConfig.Wrapper] wasn't overridden. */
    val overridesWrapper: Boolean,
    val args: Args,
) {
    /**
     * Scalars read from the `@PoseSetup` annotation. They live on the annotation
     * rather than as interface properties because KSP can read annotation
     * arguments but not property initializers.
     */
    internal data class Args(
        val generateForAllPublicComposables: Boolean,
        val previewAnnotationFqns: List<String>,
        val provideInspectionMode: Boolean,
        val maxPreviewsPerComposable: Int,
        val maxDepth: Int,
        val collectionSize: Int,
    )
}

/**
 * Finds and validates the module's single `@PoseSetup` object.
 *
 * KSP can't enumerate annotated symbols across compiled dependencies, so the
 * object must be declared in the module being processed. Config is shared
 * between modules through ordinary inheritance instead — which is why the
 * override checks below walk the whole supertype chain rather than only looking
 * at directly-declared functions.
 */
internal object PoseSetupResolver {

    fun resolve(resolver: Resolver, diagnostics: Diagnostics): PoseSetup? {
        val candidates = resolver.getSymbolsWithAnnotation(POSE_SETUP_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        if (candidates.isEmpty()) return null

        if (candidates.size > 1) {
            val names = candidates.mapNotNull { it.qualifiedName?.asString() }.sorted()
            diagnostics.hardError(
                DiagnosticCode.PG019, candidates.first(),
                "found ${candidates.size} @PoseSetup declarations in this module: ${names.joinToString()}. " +
                    "Pose config is per-module — keep exactly one and share the rest through inheritance " +
                    "(`@PoseSetup object FeaturePose : AppPoseDefaults`). " +
                    "Docs: ${DiagnosticCode.PG019.docsUrl}",
            )
            return null
        }

        val decl = candidates.single()
        val fqn = decl.qualifiedName?.asString() ?: return null
        val simple = decl.simpleName.asString()

        if (decl.classKind != ClassKind.OBJECT) {
            diagnostics.hardError(
                DiagnosticCode.PG020, decl,
                "`$simple` is annotated @PoseSetup but is a ${decl.classKind.name.lowercase()}, not an object. " +
                    "Pose calls it statically, so it must be `object $simple : PoseConfig`. " +
                    "Docs: ${DiagnosticCode.PG020.docsUrl}",
            )
            return null
        }

        val visibility = decl.getVisibility()
        if (visibility == Visibility.PRIVATE || visibility == Visibility.LOCAL) {
            diagnostics.hardError(
                DiagnosticCode.PG021, decl,
                "`$simple` is $visibility — generated previews live in a different file and can't reach it. " +
                    "Make it `internal` or `public`. " +
                    "Docs: ${DiagnosticCode.PG021.docsUrl}",
            )
            return null
        }

        if (POSE_CONFIG_FQN !in decl.allSuperTypeFqns()) {
            diagnostics.hardError(
                DiagnosticCode.PG022, decl,
                "`$simple` is annotated @PoseSetup but doesn't implement PoseConfig. " +
                    "Add `: PoseConfig` and override `Theme` to apply your theme. " +
                    "Docs: ${DiagnosticCode.PG022.docsUrl}",
            )
            return null
        }

        val setupAnn = decl.annotations.firstOrNull { it.annotationTypeFqn() == POSE_SETUP_FQN }

        return PoseSetup(
            objectFqn = fqn,
            declarationFile = decl.containingFile,
            overridesTheme = decl.overridesConfigFunction("Theme"),
            overridesWrapper = decl.overridesConfigFunction("Wrapper"),
            args = parseArgs(setupAnn),
        )
    }

    /**
     * True when the effective declaration of [name] comes from something other
     * than `PoseConfig` itself — i.e. the developer (or a shared base class)
     * overrode it, rather than inheriting the passthrough default.
     *
     * Lets the emitter skip wrapper layers that would be no-ops, keeping the
     * generated file readable.
     */
    private fun KSClassDeclaration.overridesConfigFunction(name: String): Boolean =
        // `any` rather than "first match", because the question is whether the function
        // is overridden *anywhere* in the hierarchy. KSP appears to return only the
        // most-derived declaration, which would make either form work — but that isn't
        // guaranteed anywhere, and this phrasing doesn't depend on it.
        getAllFunctions()
            .filter { it.simpleName.asString() == name }
            .any { fn ->
                val declaringFqn = (fn.parentDeclaration as? KSClassDeclaration)?.qualifiedName?.asString()
                declaringFqn != null && declaringFqn != POSE_CONFIG_FQN
            }

    private fun parseArgs(ann: KSAnnotation?): PoseSetup.Args {
        fun <T> arg(name: String): T? {
            @Suppress("UNCHECKED_CAST")
            return ann?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? T
        }
        return PoseSetup.Args(
            generateForAllPublicComposables = arg<Boolean>("generateForAllPublicComposables") ?: false,
            previewAnnotationFqns = (arg<List<*>>("previews")).orEmpty().mapNotNull(::toFqn),
            provideInspectionMode = arg<Boolean>("provideInspectionMode") ?: true,
            maxPreviewsPerComposable = arg<Int>("maxPreviewsPerComposable") ?: 8,
            maxDepth = arg<Int>("maxDepth") ?: 8,
            collectionSize = arg<Int>("collectionSize") ?: 2,
        )
    }

    private fun toFqn(value: Any?): String? = when (value) {
        is KSType -> value.declaration.qualifiedName?.asString()
        is KSClassDeclaration -> value.qualifiedName?.asString()
        else -> null
    }

    private fun KSAnnotation.annotationTypeFqn(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    private const val POSE_SETUP_FQN = "io.github.akshaychordiya.pose.PoseSetup"
    private const val POSE_CONFIG_FQN = "io.github.akshaychordiya.pose.PoseConfig"
}
