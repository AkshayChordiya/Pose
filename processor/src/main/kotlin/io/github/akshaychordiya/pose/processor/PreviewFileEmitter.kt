package io.github.akshaychordiya.pose.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Renders [PreviewPlan]s to Kotlin source and writes one file per KSFile via KSP's
 * code generator. Each generated file contains every preview function + provider
 * class for composables declared in the source file, matching the naming scheme
 * `<SourceFileBase>__Preview.kt`.
 */
public class PreviewFileEmitter(
    private val codeGenerator: CodeGenerator,
    private val options: Options,
    /**
     * Whether `androidx.compose.ui.platform.LocalInspectionMode` resolves on the
     * current compile classpath. Computed once per round by [PoseProcessor] —
     * a module could carry `compose-runtime` without `compose-ui`, and emitting
     * an unresolvable reference would break the build.
     */
    private val inspectionModeAvailable: Boolean = true,
    /**
     * Source file of the module's `@PoseSetup` object, when it has one. Declared as a
     * dependency of every generated preview - see [emitForFile].
     */
    private val setupFile: KSFile? = null,
) {

    /**
     * Emit one generated file for every source file that owns at least one plan.
     * Plans are grouped by [KSFunctionDeclaration.containingFile]; source files
     * with no plans are skipped.
     */
    public fun emitAll(plans: List<PreviewPlan>) {
        plans.groupBy { it.function.containingFile }
            .filterKeys { it != null }
            .forEach { (containingFile, filePlans) ->
                emitForFile(containingFile!!, filePlans)
            }
    }

    private fun emitForFile(containingFile: KSFile, plans: List<PreviewPlan>) {
        val pkg = plans.first().function.packageName.asString()
        val sourceBase = containingFile.fileName.substringBeforeLast('.')
        val fileName = sourceBase + "__Preview"

        val fileBuilder = FileSpec.builder(pkg, fileName)
            .addFileComment(fileHeader(plans))
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "Suppress"))
                    .addMember("%S, %S, %S", "unused", "RedundantVisibilityModifier", "UnusedImport")
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .build()
            )

        for (plan in plans) {
            fileBuilder.addFunction(previewFun(plan, pkg))
            plan.providerSlot?.let { fileBuilder.addType(providerType(it)) }
        }

        // The setup object supplies the theme, the wrapper and the scalars below, so it's
        // an input even though nothing in the user's source connects it to this composable.
        // KSP scopes `getSymbolsWithAnnotation` to the dirty file set and dirties an
        // output's declared sources when it invalidates it, so without this an edit to any
        // composable regenerated its preview in a round where the config object wasn't
        // visible - dropping the theme wrapper until the next clean build.
        val sources = listOfNotNull(containingFile, setupFile).distinct()

        fileBuilder.build().writeTo(
            codeGenerator = codeGenerator,
            dependencies = Dependencies(aggregating = false, *sources.toTypedArray()),
        )
    }

    private fun previewFun(plan: PreviewPlan, pkg: String): FunSpec {
        val baseName = plan.annotation.name.ifEmpty { plan.function.simpleName.asString() + "__Preview" } +
            plan.nameSuffix
        val builder = FunSpec.builder(baseName)
            .addModifiers(KModifier.INTERNAL)
            .addAnnotation(ClassName("androidx.compose.runtime", "Composable"))

        if (plan.annotation.previewAnnotationFqns.isEmpty()) {
            // Default: light + dark, both with showBackground = true. Emitted as two
            // explicit @Preview stampings rather than a wrapper multipreview class so
            // the annotations JAR stays free of any Compose dependency.
            builder.addAnnotation(defaultPreviewAnn(name = "Light", nightMode = false))
            builder.addAnnotation(defaultPreviewAnn(name = "Dark", nightMode = true))
        } else {
            plan.annotation.previewAnnotationFqns.forEach { fqn ->
                builder.addAnnotation(fqnToClassName(fqn))
            }
        }

        plan.providerSlot?.let { slot ->
            val providerFqn = ClassName(pkg, slot.providerSimpleName)
            val previewParamAnn = AnnotationSpec.builder(PreviewParameterAnn)
                .addMember("%T::class", providerFqn)
                .build()
            builder.addParameter(
                ParameterSpec.builder(slot.paramName, slot.paramType)
                    .addAnnotation(previewParamAnn)
                    .build()
            )
        }

        builder.addCode(bodyFor(plan))
        return builder.build()
    }

    private fun providerType(slot: ProviderSlot): TypeSpec {
        val providerBase = PreviewParameterProvider.parameterizedBy(slot.paramType)
        val sequenceOf = SequenceClassName.parameterizedBy(slot.paramType)

        val valuesProperty = PropertySpec.builder("values", sequenceOf)
            .addModifiers(KModifier.OVERRIDE)
            .initializer(slot.valuesExpr)
            .build()

        return TypeSpec.classBuilder(slot.providerSimpleName)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(providerBase)
            .addProperty(valuesProperty)
            .build()
    }

    /**
     * Builds the preview body from the inside out. Final nesting:
     *
     * ```
     * CompositionLocalProvider(LocalInspectionMode provides true) {   // outermost
     *   FeaturePose.Wrapper {                                        // PoseConfig.Wrapper
     *     FeaturePose.Theme {                                        // PoseConfig.Theme
     *       Target(...)                                              // innermost
     *     }
     *   }
     * }
     * ```
     *
     * Layers whose override was never supplied are skipped rather than emitted as
     * no-op passthroughs, so the generated file stays readable.
     *
     * The user's wrapper sits inside our inspection-mode provider deliberately —
     * innermost `CompositionLocalProvider` wins, so the wrapper keeps the final
     * say over any local Pose also sets.
     */
    private fun bodyFor(plan: PreviewPlan): CodeBlock {
        var body = targetCall(plan)

        // We emit a call to `FeaturePose.Theme { … }` - Pose never learns the theme's
        // name, so the developer's reference to it stays ordinary type-checked Kotlin.
        val setupFqn = options.setupObjectFqn
        if (setupFqn != null) {
            if (plan.annotation.wrapInTheme && options.setupOverridesTheme) {
                body = wrapInLambda(CodeBlock.of("%T.Theme", fqnToClassName(setupFqn)), body)
            }
            if (options.setupOverridesWrapper) {
                body = wrapInLambda(CodeBlock.of("%T.Wrapper", fqnToClassName(setupFqn)), body)
            }
        }
        if (options.provideInspectionMode && inspectionModeAvailable) {
            body = wrapInLambda(
                CodeBlock.of("%M(%M provides true)", CompositionLocalProviderFn, LocalInspectionModeProp),
                body,
            )
        }
        return body
    }

    private fun targetCall(plan: PreviewPlan): CodeBlock {
        val target = plan.function
        val call = CodeBlock.builder()
            .add("%M(", MemberName(target.packageName.asString(), target.simpleName.asString()))

        val callArgs = buildList {
            plan.providerSlot?.let { add(it.paramName to CodeBlock.of("%L", it.paramName)) }
            plan.otherArgs.forEach { add(it.paramName to it.expr) }
        }
        if (callArgs.isEmpty()) {
            call.add(")\n")
        } else {
            call.add("\n  ⇥")
            callArgs.forEachIndexed { i, (name, expr) ->
                if (i > 0) call.add(",\n")
                call.add("%L = %L", name, expr)
            }
            call.add(",\n⇤)\n")
        }
        return call.build()
    }

    /** `<header> { <inner> }` with KotlinPoet indent markers. */
    private fun wrapInLambda(header: CodeBlock, inner: CodeBlock): CodeBlock =
        CodeBlock.builder()
            .add("%L {\n  ⇥", header)
            .add(inner)
            .add("⇤}\n")
            .build()

    private fun fileHeader(plans: List<PreviewPlan>): String = buildString {
        if (options.vogue) appendLine(vogueBanner(plans.size))
        appendLine("GENERATED by io.github.akshaychordiya.pose — DO NOT EDIT")
        val firstFile = plans.first().function.containingFile?.fileName
        if (firstFile != null) appendLine("Source: $firstFile")
        for ((i, plan) in plans.withIndex()) {
            if (i > 0) appendLine()
            val fn = plan.function
            val line = (fn.location as? FileLocation)?.lineNumber ?: -1
            appendLine("Target: ${fn.qualifiedName?.asString()}${if (line > 0) " (line $line)" else ""}")
            appendLine("  Resolution:")
            plan.resolutionNotes.forEach { note ->
                appendLine("    ${note.paramName} -> ${note.tier} (${note.note})")
            }
        }
    }

    /**
     * Easter egg. Off unless `pose.vogue=true`, deliberately absent from the README —
     * findable by anyone who reads `Options.KnownKeys` or this file.
     *
     * Deterministic by construction: the only input is the plan count. Pose's whole
     * contract is byte-identical output for byte-identical input, it's a comment, so it
     * never reaches compiled output either.
     *
     * If you're tidying up: this is intentional, not dead code.
     */
    private fun vogueBanner(previewCount: Int): String {
        val posed = if (previewCount == 1) "1 pose struck" else "$previewCount poses struck"
        return """
            ┌──────────────────────────────┐
              \o/   $posed,
               |    0 maintained.
              / \
            └──────────────────────────────┘
        """.trimIndent()
    }

    /**
     * Stamped for each generated preview when the user hasn't overridden `previews`.
     * `UI_MODE_NIGHT_NO` = 0x10 (16); `UI_MODE_NIGHT_YES` = 0x20 (32). We inline the
     * numeric constants rather than importing `android.content.res.Configuration` so
     * the emitted file has one fewer import — cleaner grep target.
     */
    private fun defaultPreviewAnn(name: String, nightMode: Boolean): AnnotationSpec =
        AnnotationSpec.builder(ClassName("androidx.compose.ui.tooling.preview", "Preview"))
            .addMember("name = %S", name)
            .addMember("uiMode = %L", if (nightMode) 0x20 else 0x10)
            .addMember("showBackground = true")
            .build()

    private fun fqnToClassName(fqn: String): ClassName {
        val idx = fqn.lastIndexOf('.')
        return ClassName(fqn.substring(0, idx), fqn.substring(idx + 1))
    }

    private companion object {
        val PreviewParameterAnn = ClassName("androidx.compose.ui.tooling.preview", "PreviewParameter")
        val PreviewParameterProvider = ClassName("androidx.compose.ui.tooling.preview", "PreviewParameterProvider")
        val SequenceClassName = ClassName("kotlin.sequences", "Sequence")
        val CompositionLocalProviderFn = MemberName("androidx.compose.runtime", "CompositionLocalProvider")
        val LocalInspectionModeProp = MemberName("androidx.compose.ui.platform", "LocalInspectionMode")
    }
}
