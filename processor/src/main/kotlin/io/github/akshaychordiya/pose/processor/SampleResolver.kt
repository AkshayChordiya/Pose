package io.github.akshaychordiya.pose.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Resolves a fake value for one parameter or nested property via the tier ladder:
 * T0 default → T1 FQN table → T2 structural synth → refuse.
 *
 * Callers pass a fresh [Context] per top-level parameter (see [resolveParameter])
 * so cycle-guard state does not leak across parameters of the same composable.
 */
public class SampleResolver(
    private val options: Options,
) {

    /** Per-descent state - cycle guard set + depth counter. */
    public class Context(public val paramPath: String) {
        internal val visited: MutableSet<String> = mutableSetOf()
        internal var depth: Int = 0
    }

    /**
     * Resolve the sample for a top-level composable parameter. Returns [SampleExpr.Omit]
     * when [hasDefault] is `true`, otherwise walks the type per the tier ladder.
     */
    public fun resolveParameter(
        paramName: String,
        typeRef: KSTypeReference,
        hasDefault: Boolean,
    ): SampleExpr {
        if (hasDefault) return SampleExpr.Omit
        val ctx = Context(paramName)
        return resolveType(typeRef.resolve(), ctx)
    }

    /**
     * Recursive walker. Public because [PreviewFileEmitter] also uses it for
     * sealed subtype resolution.
     */
    public fun resolveType(type: KSType, ctx: Context): SampleExpr {
        val fqn = type.fullyQualifiedName() ?: return SampleExpr.Refuse(
            RefusalReason.NoStrategy(ctx.paramPath, "<unresolved>", listOf("T2"))
        )

        // T1a - refuse-category
        FqnTable.RefuseFqns[fqn]?.let { category ->
            return SampleExpr.Refuse(RefusalReason.RefuseCategory(ctx.paramPath, fqn, category))
        }
        val decl = type.declaration as? KSClassDeclaration
        if (decl != null) {
            val superFqns = decl.allSuperTypeFqns()
            FqnTable.refuseCategoryFor(fqn, superFqns)?.let { category ->
                return SampleExpr.Refuse(RefusalReason.RefuseCategory(ctx.paramPath, fqn, category))
            }
        }

        // T1b - simple FQN table
        FqnTable.SimpleEmitters[fqn]?.let { code ->
            return SampleExpr.Emit(code, tier = 1, note = "FQN table")
        }

        // T1c - generic FQN table (Flow<T>, StateFlow<T>, Result<T>, ...)
        FqnTable.GenericEmitters[fqn]?.let { emitter ->
            val arg = type.arguments.firstOrNull()?.type?.resolve()
                ?: return SampleExpr.Refuse(
                    RefusalReason.NoStrategy(ctx.paramPath, fqn, listOf("T1-generic-arg-missing"))
                )
            val inner = when (val nested = resolveType(arg, ctx.child(".inner"))) {
                SampleExpr.Omit -> return SampleExpr.Refuse(
                    RefusalReason.NoStrategy(ctx.paramPath, fqn, listOf("T1-generic-omit-illegal"))
                )
                is SampleExpr.Emit -> nested.code
                is SampleExpr.Refuse -> return nested
            }
            return SampleExpr.Emit(emitter.emit(inner), tier = 1, note = "FQN generic")
        }

        // T1d - developer-provided `companion.previewSamples`. Used both at the top
        // level (via @PreviewParameter — planner-side) AND during nested recursion
        // here, where we take the first value from the sequence inline. This unlocks
        // types like sealed classes whose subtypes are hidden behind a factory.
        if (decl != null && hasCompanionPreviewSamples(decl)) {
            return SampleExpr.Emit(
                CodeBlock.of("%T.previewSamples.first()", decl.toClassName()),
                tier = 1,
                note = "companion.previewSamples (nested)",
            )
        }

        // T2 - structural synthesis
        return synthesize(type, decl, fqn, ctx)
    }

    private fun synthesize(
        type: KSType,
        decl: KSClassDeclaration?,
        fqn: String,
        ctx: Context,
    ): SampleExpr {
        // Cycle guard
        if (!ctx.visited.add(fqn)) {
            return if (type.nullability == Nullability.NULLABLE) {
                SampleExpr.Emit(CodeBlock.of("null"), tier = 2, note = "cycle→null")
            } else {
                SampleExpr.Refuse(RefusalReason.Cycle(ctx.paramPath, ctx.visited.toList() + fqn))
            }
        }
        if (++ctx.depth > options.maxDepth) {
            return SampleExpr.Refuse(RefusalReason.Cycle(ctx.paramPath, ctx.visited.toList() + fqn))
        }
        try {
            // Primitives via FQN
            primitiveExpression(fqn, ctx.paramPath)?.let { return SampleExpr.Emit(it, 2, "primitive") }

            if (decl == null) return SampleExpr.Refuse(
                RefusalReason.NoStrategy(ctx.paramPath, fqn, listOf("T2-decl-missing"))
            )

            // Enum
            if (decl.classKind == ClassKind.ENUM_CLASS) {
                return SampleExpr.Emit(
                    CodeBlock.of("%T.entries.first()", decl.toClassName()),
                    tier = 2, note = "enum",
                )
            }
            // object / data object
            if (decl.classKind == ClassKind.OBJECT) {
                return SampleExpr.Emit(CodeBlock.of("%T", decl.toClassName()), tier = 2, note = "object")
            }
            // Collections
            collectionExpression(fqn, type, ctx)?.let { return it }

            // Sealed - the composable-level fan-out logic in the emitter handles subtype
            // selection. If we hit a sealed here, it means the caller is a nested field;
            // pick a single non-refused subtype (declaration order).
            if (decl.modifiers.any { it == Modifier.SEALED }) {
                return resolveSealedInline(decl, ctx)
            }

            // Function types
            val functionTypeExpr = FunctionTypeSynthesizer.tryEmit(decl, type)
            if (functionTypeExpr != null) return functionTypeExpr

            // Interface / abstract - refuse
            if (decl.classKind == ClassKind.INTERFACE ||
                decl.modifiers.any { it == Modifier.ABSTRACT }
            ) {
                return SampleExpr.Refuse(
                    RefusalReason.NoStrategy(ctx.paramPath, fqn, listOf("T2-interface-no-impl"))
                )
            }

            // data class / class with public primary constructor / value class
            return synthesizeClassLike(decl, ctx)
        } finally {
            ctx.depth--
            ctx.visited.remove(fqn)
        }
    }

    private fun synthesizeClassLike(decl: KSClassDeclaration, ctx: Context): SampleExpr {
        // The class ITSELF must be accessible from the generated file. Data classes
        // marked `private` (or ones inside a sealed hierarchy where variants are
        // hidden behind a factory) will have a synthesized constructor that KSP
        // reports as public — so we can't rely on the constructor visibility alone.
        val classVisibility = decl.getVisibility()
        if (classVisibility != Visibility.PUBLIC && classVisibility != Visibility.INTERNAL) {
            return SampleExpr.Refuse(
                RefusalReason.NoStrategy(ctx.paramPath, decl.qualifiedNameOrEmpty(), listOf("T2-inaccessible-class-$classVisibility"))
            )
        }
        val ctor = decl.primaryConstructor
            ?: return SampleExpr.Refuse(RefusalReason.NoStrategy(ctx.paramPath, decl.qualifiedNameOrEmpty(), listOf("T2-no-primary-ctor")))
        if (ctor.getVisibility() != Visibility.PUBLIC && ctor.getVisibility() != Visibility.INTERNAL) {
            return SampleExpr.Refuse(RefusalReason.NoStrategy(ctx.paramPath, decl.qualifiedNameOrEmpty(), listOf("T2-non-public-ctor")))
        }
        val args = mutableListOf<CodeBlock>()
        for (param in ctor.parameters) {
            val paramName = param.name?.asString() ?: continue
            val childCtx = ctx.child(".$paramName")
            when (val result = resolveTypeWithDefault(param.type, param.hasDefault, childCtx)) {
                SampleExpr.Omit -> Unit
                is SampleExpr.Emit -> args += CodeBlock.of("%L = %L", paramName, result.code)
                is SampleExpr.Refuse -> return result
            }
        }
        val builder = CodeBlock.builder().add("%T(", decl.toClassName())
        args.forEachIndexed { i, cb ->
            if (i > 0) builder.add(", ")
            builder.add(cb)
        }
        builder.add(")")
        return SampleExpr.Emit(builder.build(), tier = 2, note = "structural")
    }

    private fun resolveTypeWithDefault(
        typeRef: KSTypeReference,
        hasDefault: Boolean,
        ctx: Context,
    ): SampleExpr = if (hasDefault) SampleExpr.Omit else resolveType(typeRef.resolve(), ctx)

    private fun resolveSealedInline(decl: KSClassDeclaration, ctx: Context): SampleExpr {
        val subtypes = decl.getSealedSubclassesOrEmpty()
        if (subtypes.isEmpty()) {
            return SampleExpr.Refuse(RefusalReason.InvisibleSealedSubtype(ctx.paramPath, decl.qualifiedNameOrEmpty()))
        }
        for (sub in subtypes.sortedBy { it.simpleName.asString() }) {
            val subType = sub.asStarProjectedType()
            val result = resolveType(subType, ctx.child("<${sub.simpleName.asString()}>"))
            if (result is SampleExpr.Emit || result is SampleExpr.Omit) return result
        }
        return SampleExpr.Refuse(RefusalReason.NoStrategy(ctx.paramPath, decl.qualifiedNameOrEmpty(), listOf("T2-sealed-all-refused")))
    }

    private fun collectionExpression(fqn: String, type: KSType, ctx: Context): SampleExpr? {
        when (fqn) {
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.Collection" -> {
                val elem = type.arguments.firstOrNull()?.type?.resolve() ?: return null
                val inner = (resolveType(elem, ctx.child("[]")) as? SampleExpr.Emit)?.code ?: return null
                val elems = List(options.collectionSize) { inner }
                val builder = CodeBlock.builder().add("listOf(")
                elems.forEachIndexed { i, cb -> if (i > 0) builder.add(", "); builder.add(cb) }
                builder.add(")")
                return SampleExpr.Emit(builder.build(), 2, "list")
            }
            "kotlinx.collections.immutable.ImmutableList",
            "kotlinx.collections.immutable.PersistentList",
            "kotlinx.collections.immutable.ImmutableCollection" -> {
                val elem = type.arguments.firstOrNull()?.type?.resolve() ?: return null
                val inner = (resolveType(elem, ctx.child("[]")) as? SampleExpr.Emit)?.code ?: return null
                val builder = CodeBlock.builder()
                    .add("%M(", MemberName("kotlinx.collections.immutable", "persistentListOf"))
                repeat(options.collectionSize) { i -> if (i > 0) builder.add(", "); builder.add(inner) }
                builder.add(")")
                return SampleExpr.Emit(builder.build(), 2, "immutable list")
            }
            "kotlinx.collections.immutable.ImmutableSet",
            "kotlinx.collections.immutable.PersistentSet" -> {
                val elem = type.arguments.firstOrNull()?.type?.resolve() ?: return null
                val inner = (resolveType(elem, ctx.child("[]")) as? SampleExpr.Emit)?.code ?: return null
                return SampleExpr.Emit(
                    CodeBlock.of("%M(%L)", MemberName("kotlinx.collections.immutable", "persistentSetOf"), inner),
                    2, "immutable set",
                )
            }
            "kotlinx.collections.immutable.ImmutableMap",
            "kotlinx.collections.immutable.PersistentMap" -> {
                val kType = type.arguments.getOrNull(0)?.type?.resolve() ?: return null
                val vType = type.arguments.getOrNull(1)?.type?.resolve() ?: return null
                val kInner = (resolveType(kType, ctx.child(".key")) as? SampleExpr.Emit)?.code ?: return null
                val vInner = (resolveType(vType, ctx.child(".value")) as? SampleExpr.Emit)?.code ?: return null
                return SampleExpr.Emit(
                    CodeBlock.of("%M(%L to %L)", MemberName("kotlinx.collections.immutable", "persistentMapOf"), kInner, vInner),
                    2, "immutable map",
                )
            }
            "kotlin.collections.Set", "kotlin.collections.MutableSet" -> {
                val elem = type.arguments.firstOrNull()?.type?.resolve() ?: return null
                val inner = (resolveType(elem, ctx.child("[]")) as? SampleExpr.Emit)?.code ?: return null
                return SampleExpr.Emit(CodeBlock.of("setOf(%L)", inner), 2, "set")
            }
            "kotlin.collections.Map", "kotlin.collections.MutableMap" -> {
                val kType = type.arguments.getOrNull(0)?.type?.resolve() ?: return null
                val vType = type.arguments.getOrNull(1)?.type?.resolve() ?: return null
                val kInner = (resolveType(kType, ctx.child(".key")) as? SampleExpr.Emit)?.code ?: return null
                val vInner = (resolveType(vType, ctx.child(".value")) as? SampleExpr.Emit)?.code ?: return null
                return SampleExpr.Emit(CodeBlock.of("mapOf(%L to %L)", kInner, vInner), 2, "map")
            }
            "kotlin.Pair" -> {
                val a = type.arguments.getOrNull(0)?.type?.resolve() ?: return null
                val b = type.arguments.getOrNull(1)?.type?.resolve() ?: return null
                val aInner = (resolveType(a, ctx.child(".first")) as? SampleExpr.Emit)?.code ?: return null
                val bInner = (resolveType(b, ctx.child(".second")) as? SampleExpr.Emit)?.code ?: return null
                return SampleExpr.Emit(CodeBlock.of("%L to %L", aInner, bInner), 2, "pair")
            }
            // Must be handled here for the same reason as Pair: the type arguments are
            // known at the use site, but Triple's own constructor takes type *variables*,
            // so falling through to structural synthesis refuses on `A`.
            "kotlin.Triple" -> {
                val a = type.arguments.getOrNull(0)?.type?.resolve() ?: return null
                val b = type.arguments.getOrNull(1)?.type?.resolve() ?: return null
                val c = type.arguments.getOrNull(2)?.type?.resolve() ?: return null
                val aInner = (resolveType(a, ctx.child(".first")) as? SampleExpr.Emit)?.code ?: return null
                val bInner = (resolveType(b, ctx.child(".second")) as? SampleExpr.Emit)?.code ?: return null
                val cInner = (resolveType(c, ctx.child(".third")) as? SampleExpr.Emit)?.code ?: return null
                return SampleExpr.Emit(CodeBlock.of("Triple(%L, %L, %L)", aInner, bInner, cInner), 2, "triple")
            }
        }
        return null
    }

    private fun primitiveExpression(fqn: String, paramPath: String): CodeBlock? = when (fqn) {
        "kotlin.Boolean" -> CodeBlock.of("false")
        "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long" -> CodeBlock.of("0")
        "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong" -> CodeBlock.of("0u")
        "kotlin.Float" -> CodeBlock.of("0.0f")
        "kotlin.Double" -> CodeBlock.of("0.0")
        "kotlin.Char" -> CodeBlock.of("' '")
        "kotlin.String" -> CodeBlock.of("%S", titlecaseIfInformative(paramPath))
        else -> null
    }

    private fun titlecaseIfInformative(paramPath: String): String {
        val leaf = paramPath.substringAfterLast('.').substringAfterLast(' ')
        if (leaf.length < 2 || !leaf.all { it.isLetter() }) return "Sample"
        return leaf.replaceFirstChar { it.uppercase() }
    }
}

/** Per-descent path helper - appends a segment without mutating [visited]/depth of the parent. */
internal fun SampleResolver.Context.child(suffix: String): SampleResolver.Context {
    val next = SampleResolver.Context(paramPath + suffix)
    next.visited.addAll(this.visited)
    next.depth = this.depth
    return next
}

internal fun KSType.fullyQualifiedName(): String? =
    declaration.qualifiedName?.asString()

internal fun KSClassDeclaration.qualifiedNameOrEmpty(): String =
    qualifiedName?.asString() ?: ""

/** True if the type's companion exposes `val previewSamples: Sequence<T>`. */
internal fun hasCompanionPreviewSamples(decl: KSClassDeclaration): Boolean {
    val companion = decl.declarations
        .filterIsInstance<KSClassDeclaration>()
        .firstOrNull { it.isCompanionObject } ?: return false
    val prop = companion.getAllProperties()
        .firstOrNull { it.simpleName.asString() == "previewSamples" } ?: return false
    return prop.type.resolve().declaration.qualifiedName?.asString() == "kotlin.sequences.Sequence"
}

internal fun KSClassDeclaration.getSealedSubclassesOrEmpty(): List<KSClassDeclaration> = try {
    getSealedSubclasses().toList()
} catch (_: Throwable) {
    emptyList()
}

/** Transitive supertype FQN set. KSP does not ship this out of the box. */
internal fun KSClassDeclaration.allSuperTypeFqns(): Set<String> {
    val out = mutableSetOf<String>()
    val stack = ArrayDeque<KSClassDeclaration>().apply { add(this@allSuperTypeFqns) }
    while (stack.isNotEmpty()) {
        val current = stack.removeFirst()
        for (superRef in current.superTypes) {
            val superDecl = superRef.resolve().declaration as? KSClassDeclaration ?: continue
            val superFqn = superDecl.qualifiedName?.asString() ?: continue
            if (out.add(superFqn)) stack.add(superDecl)
        }
    }
    return out
}
