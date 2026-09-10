package io.github.akshaychordiya.pose.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName

/**
 * Frozen well-known-FQN table (Tier 1 of the sample resolver).
 *
 * Split into three groups:
 *  - [SimpleEmitters]  — types with a fixed expression (no recursion on generics).
 *  - [GenericEmitters] — types that recurse into a type argument (`Flow<T>` etc.).
 *  - [RefuseFqns]      — types that cause the whole composable to be refused (PG003).
 */
public object FqnTable {

    /** `fqn → CodeBlock` for types the plugin knows how to construct inline. */
    public val SimpleEmitters: Map<String, CodeBlock> = mapOf(
        // Compose value classes
        "androidx.compose.ui.graphics.Color" to code("%T(0xFFCCCCCC.toInt())", cn("androidx.compose.ui.graphics", "Color")),
        "androidx.compose.ui.unit.Dp" to code("16.%M", mn("androidx.compose.ui.unit", "dp")),
        "androidx.compose.ui.unit.TextUnit" to code("16.%M", mn("androidx.compose.ui.unit", "sp")),
        "androidx.compose.ui.geometry.Offset" to code("%T.Zero", cn("androidx.compose.ui.geometry", "Offset")),
        "androidx.compose.ui.geometry.Size" to code("%T.Zero", cn("androidx.compose.ui.geometry", "Size")),
        "androidx.compose.ui.unit.DpSize" to code("%T(48.%M, 48.%M)",
            cn("androidx.compose.ui.unit", "DpSize"),
            mn("androidx.compose.ui.unit", "dp"),
            mn("androidx.compose.ui.unit", "dp")),
        "androidx.compose.ui.unit.DpOffset" to code("%T.Zero", cn("androidx.compose.ui.unit", "DpOffset")),
        "androidx.compose.ui.unit.IntSize" to code("%T.Zero", cn("androidx.compose.ui.unit", "IntSize")),
        "androidx.compose.ui.unit.IntOffset" to code("%T.Zero", cn("androidx.compose.ui.unit", "IntOffset")),
        "androidx.compose.ui.unit.Constraints" to code("%T()", cn("androidx.compose.ui.unit", "Constraints")),

        // Compose layout
        "androidx.compose.foundation.layout.PaddingValues" to code("%M(0.%M)",
            mn("androidx.compose.foundation.layout", "PaddingValues"),
            mn("androidx.compose.ui.unit", "dp")),
        "androidx.compose.foundation.layout.WindowInsets" to code("%M(0)",
            mn("androidx.compose.foundation.layout", "WindowInsets")),

        // Compose graphics — inlined to keep the generated file free of a runtime dep.
        // ColorPainter and ImageVector.Builder both come from androidx.compose.ui:ui-graphics,
        // which every consumer already has via ui-tooling-preview.
        "androidx.compose.ui.graphics.painter.Painter" to code(
            "%T(%T(0xFFCCCCCC.toInt()))",
            cn("androidx.compose.ui.graphics.painter", "ColorPainter"),
            cn("androidx.compose.ui.graphics", "Color"),
        ),
        "androidx.compose.ui.graphics.vector.ImageVector" to code(
            "%T(name = %S, defaultWidth = 24.%M, defaultHeight = 24.%M, viewportWidth = 24f, viewportHeight = 24f).build()",
            cn("androidx.compose.ui.graphics.vector", "ImageVector.Builder"),
            "Placeholder",
            mn("androidx.compose.ui.unit", "dp"),
            mn("androidx.compose.ui.unit", "dp"),
        ),
        "androidx.compose.ui.graphics.ImageBitmap" to code("%M(64, 64)",
            mn("androidx.compose.ui.graphics", "ImageBitmap")),

        // Compose core types that are interfaces/abstract and would otherwise refuse.
        "androidx.compose.ui.Modifier" to code("%T", cn("androidx.compose.ui", "Modifier")),
        "androidx.compose.ui.graphics.Shape" to code("%M",
            mn("androidx.compose.ui.graphics", "RectangleShape")),
        "androidx.compose.ui.graphics.Brush" to code("%T(%T(0xFFCCCCCC.toInt()))",
            cn("androidx.compose.ui.graphics", "SolidColor"),
            cn("androidx.compose.ui.graphics", "Color")),

        // Text
        "androidx.compose.ui.text.AnnotatedString" to code("%T(\"Sample\")",
            cn("androidx.compose.ui.text", "AnnotatedString")),
        "androidx.compose.ui.text.input.TextFieldValue" to code("%T(\"Sample\")",
            cn("androidx.compose.ui.text.input", "TextFieldValue")),
        "androidx.compose.ui.text.font.FontFamily" to code("%T.Default",
            cn("androidx.compose.ui.text.font", "FontFamily")),
        "androidx.compose.ui.text.TextStyle" to code("%T.Default",
            cn("androidx.compose.ui.text", "TextStyle")),

        // java.time
        "java.time.LocalDate" to code("%T.of(2024, 1, 15)", cn("java.time", "LocalDate")),
        "java.time.LocalDateTime" to code("%T.of(2024, 1, 15, 12, 0)", cn("java.time", "LocalDateTime")),
        "java.time.LocalTime" to code("%T.of(12, 0)", cn("java.time", "LocalTime")),
        "java.time.Instant" to code("%T.EPOCH", cn("java.time", "Instant")),
        "java.time.Duration" to code("%T.ZERO", cn("java.time", "Duration")),
        "java.time.Period" to code("%T.ZERO", cn("java.time", "Period")),
        "java.time.ZonedDateTime" to code(
            "%T.of(%T.of(2024, 1, 15, 12, 0), %T.UTC)",
            cn("java.time", "ZonedDateTime"),
            cn("java.time", "LocalDateTime"),
            cn("java.time", "ZoneOffset"),
        ),
        "java.time.YearMonth" to code("%T.of(2024, 1)", cn("java.time", "YearMonth")),
        "java.time.Year" to code("%T.of(2024)", cn("java.time", "Year")),
        "java.time.OffsetDateTime" to code(
            "%T.of(2024, 1, 15, 12, 0, 0, 0, %T.UTC)",
            cn("java.time", "OffsetDateTime"),
            cn("java.time", "ZoneOffset"),
        ),

        // java.math
        "java.math.BigDecimal" to code("%T.ZERO", cn("java.math", "BigDecimal")),
        "java.math.BigInteger" to code("%T.ZERO", cn("java.math", "BigInteger")),

        // UUIDs
        "kotlin.uuid.Uuid" to code("%T.NIL", cn("kotlin.uuid", "Uuid")),
        "java.util.UUID" to code("%T(0L, 0L)", cn("java.util", "UUID")),

        // kotlinx.datetime (if present on classpath)
        "kotlinx.datetime.LocalDate" to code("%T(2024, 1, 15)", cn("kotlinx.datetime", "LocalDate")),
        "kotlinx.datetime.LocalDateTime" to code("%T(2024, 1, 15, 12, 0)", cn("kotlinx.datetime", "LocalDateTime")),
        "kotlinx.datetime.Instant" to code("%T.fromEpochSeconds(0)", cn("kotlinx.datetime", "Instant")),
        "kotlinx.datetime.LocalTime" to code("%T(12, 0)", cn("kotlinx.datetime", "LocalTime")),
        "kotlinx.datetime.TimeZone" to code("%T.UTC", cn("kotlinx.datetime", "TimeZone")),

        // kotlin.time
        "kotlin.time.Duration" to code("%T.ZERO", cn("kotlin.time", "Duration")),
        "kotlin.time.Instant" to code("%T.fromEpochSeconds(0)", cn("kotlin.time", "Instant")),

        // Android
        "android.net.Uri" to code("%T.parse(\"content://com.example/1\")", cn("android.net", "Uri")),
    )

    /** `fqn → template for the generic wrapper`; `%L` is replaced with the recursed inner expression. */
    public val GenericEmitters: Map<String, GenericEmitter> = mapOf(
        "kotlinx.coroutines.flow.Flow" to GenericEmitter { inner ->
            code("%M(%L)", mn("kotlinx.coroutines.flow", "flowOf"), inner)
        },
        "kotlinx.coroutines.flow.StateFlow" to GenericEmitter { inner ->
            code("%M(%L)", mn("kotlinx.coroutines.flow", "MutableStateFlow"), inner)
        },
        "kotlinx.coroutines.flow.SharedFlow" to GenericEmitter { inner ->
            code("%M<%L>(replay = 1).apply { tryEmit(%L) }",
                mn("kotlinx.coroutines.flow", "MutableSharedFlow"), inner, inner)
        },
        "androidx.compose.runtime.State" to GenericEmitter { inner ->
            code("%M { %M(%L) }",
                mn("androidx.compose.runtime", "remember"),
                mn("androidx.compose.runtime", "mutableStateOf"),
                inner)
        },
        "androidx.compose.runtime.MutableState" to GenericEmitter { inner ->
            code("%M { %M(%L) }",
                mn("androidx.compose.runtime", "remember"),
                mn("androidx.compose.runtime", "mutableStateOf"),
                inner)
        },
        "kotlin.Result" to GenericEmitter { inner ->
            code("%T.success(%L)", cn("kotlin", "Result"), inner)
        },
    )

    /** Types that cause the whole composable to be refused (PG003). */
    public val RefuseFqns: Map<String, String> = mapOf(
        "androidx.lifecycle.ViewModel" to "ViewModel",
        "androidx.lifecycle.AndroidViewModel" to "ViewModel",
        "androidx.lifecycle.SavedStateHandle" to "SavedStateHandle",
        "androidx.navigation.NavController" to "NavController",
        "androidx.navigation.NavHostController" to "NavController",
        "androidx.navigation.NavBackStackEntry" to "NavController",
        "android.content.Context" to "Context",
        "android.app.Activity" to "Context",
        "androidx.fragment.app.Fragment" to "Context",
        "android.graphics.Bitmap" to "Bitmap",
        "kotlinx.coroutines.channels.Channel" to "Channel",
        "kotlinx.coroutines.CoroutineScope" to "CoroutineScope",
        "coil.compose.AsyncImagePainter" to "AsyncImagePainter",
        "coil.compose.SubcomposeAsyncImagePainter" to "AsyncImagePainter",
        "coil3.compose.AsyncImagePainter" to "AsyncImagePainter",
        "coil3.compose.SubcomposeAsyncImagePainter" to "AsyncImagePainter",
    )

    /**
     * `true` if [fqn] transitively extends any refuse-category root — e.g. any
     * `ViewModel` subclass or any `@HiltViewModel`-annotated class.
     */
    public fun refuseCategoryFor(fqn: String, supertypeFqns: Set<String>): String? {
        RefuseFqns[fqn]?.let { return it }
        for (superType in supertypeFqns) {
            RefuseFqns[superType]?.let { return it }
        }
        return null
    }

    public fun interface GenericEmitter {
        public fun emit(innerExpression: CodeBlock): CodeBlock
    }

    private fun cn(pkg: String, name: String) = ClassName(pkg, name)
    private fun mn(pkg: String, name: String) = MemberName(pkg, name)
    private fun code(format: String, vararg args: Any) = CodeBlock.of(format, *args)
}
