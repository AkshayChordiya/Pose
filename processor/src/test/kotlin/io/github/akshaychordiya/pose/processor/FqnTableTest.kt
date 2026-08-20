package io.github.akshaychordiya.pose.processor

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.akshaychordiya.pose.processor.testing.CompileHarness
import org.junit.Test

/**
 * Spot-checks the T1 FQN-table emitters against the exact literals each entry
 * advertises in [FqnTable]. If someone changes an emitter, this suite catches
 * it — behavior of a table-driven feature deserves table-driven tests.
 */
class FqnTableTest {

    @Test
    fun `Color parameter emits the neutral grey literal`() {
        val generated = compileOne(
            paramSignature = "tint: androidx.compose.ui.graphics.Color",
            stubs = listOf(colorStub),
        )
        assertThat(generated).contains("tint = Color(0xFFCCCCCC.toInt())")
    }

    @Test
    fun `Dp parameter emits 16 dp`() {
        val generated = compileOne(
            paramSignature = "corner: androidx.compose.ui.unit.Dp",
            stubs = listOf(dpStub),
        )
        assertThat(generated).contains("corner = 16.dp")
    }

    @Test
    fun `StateFlow of String emits MutableStateFlow with a synthesized inner value`() {
        val generated = compileOne(
            paramSignature = "titleStream: kotlinx.coroutines.flow.StateFlow<String>",
            stubs = listOf(stateFlowStub),
        )
        assertThat(generated).contains("titleStream = MutableStateFlow(")
        // The inner String is filled by the parameter-name placeholder rule.
        assertThat(generated).contains("MutableStateFlow(\"Inner\")")
    }

    @Test
    fun `Result of Int emits Result_success wrapping the inner sample`() {
        val generated = compileOne(
            paramSignature = "lastRun: kotlin.Result<Int>",
            stubs = emptyList(),
        )
        assertThat(generated).contains("lastRun = Result.success(0)")
    }

    private fun compileOne(paramSignature: String, stubs: List<SourceFile>): String {
        val source = SourceFile.kotlin(
            "Target.kt",
            """
            package sample

            import androidx.compose.runtime.Composable
            import io.github.akshaychordiya.pose.Pose

            @Composable
            @Pose
            fun Target($paramSignature) { }
            """.trimIndent()
        )
        val result = CompileHarness.compile(listOf(source) + stubs)
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        return result.generatedFile("Target__Preview.kt").readText()
    }

    // ---- Minimal stubs for the Compose / coroutines FQNs the emitters reference. ----

    private val colorStub = SourceFile.kotlin(
        "ColorStub.kt",
        """
        package androidx.compose.ui.graphics
        class Color(val value: Int)
        """.trimIndent()
    )

    private val dpStub = SourceFile.kotlin(
        "DpStub.kt",
        """
        package androidx.compose.ui.unit
        class Dp(val value: Float)
        val Int.dp: Dp get() = Dp(this.toFloat())
        """.trimIndent()
    )

    private val stateFlowStub = SourceFile.kotlin(
        "StateFlowStub.kt",
        """
        package kotlinx.coroutines.flow

        interface StateFlow<out T> { val value: T }
        interface MutableStateFlow<T> : StateFlow<T>
        @Suppress("FunctionName")
        fun <T> MutableStateFlow(value: T): MutableStateFlow<T> = object : MutableStateFlow<T> {
            override val value: T = value
        }
        fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = this
        """.trimIndent()
    )

    // ---- T1 additions: Compose core types, modern time/UUID, java.math, immutable collections ----

    @Test
    fun `Modifier parameter emits the companion object`() {
        val generated = compileOne(
            paramSignature = "modifier: androidx.compose.ui.Modifier",
            stubs = listOf(modifierStub),
        )
        assertThat(generated).contains("modifier = Modifier")
    }

    @Test
    fun `Shape parameter emits RectangleShape`() {
        val generated = compileOne(
            paramSignature = "shape: androidx.compose.ui.graphics.Shape",
            stubs = listOf(shapeStub),
        )
        assertThat(generated).contains("shape = RectangleShape")
    }

    @Test
    fun `Brush parameter emits a SolidColor of the neutral grey`() {
        val generated = compileOne(
            paramSignature = "background: androidx.compose.ui.graphics.Brush",
            stubs = listOf(colorStub, brushStub),
        )
        assertThat(generated).contains("background = SolidColor(Color(0xFFCCCCCC.toInt()))")
    }

    @Test
    fun `TextStyle parameter emits the Default companion value`() {
        val generated = compileOne(
            paramSignature = "style: androidx.compose.ui.text.TextStyle",
            stubs = listOf(textStyleStub),
        )
        assertThat(generated).contains("style = TextStyle.Default")
    }

    @Test
    fun `kotlin time Instant parameter emits epoch`() {
        val generated = compileOne(
            paramSignature = "timestamp: kotlin.time.Instant",
            stubs = emptyList(),
        )
        assertThat(generated).contains("timestamp = Instant.fromEpochSeconds(0)")
    }

    @Test
    fun `kotlin uuid Uuid parameter emits NIL`() {
        val generated = compileOne(
            paramSignature = "id: kotlin.uuid.Uuid",
            stubs = emptyList(),
        )
        assertThat(generated).contains("id = Uuid.NIL")
    }

    @Test
    fun `java util UUID parameter emits the zero UUID`() {
        val generated = compileOne(
            paramSignature = "id: java.util.UUID",
            stubs = emptyList(),
        )
        assertThat(generated).contains("id = UUID(0L, 0L)")
    }

    @Test
    fun `kotlinx datetime LocalTime parameter emits noon`() {
        val generated = compileOne(
            paramSignature = "time: kotlinx.datetime.LocalTime",
            stubs = listOf(kotlinxDatetimeStub),
        )
        assertThat(generated).contains("time = LocalTime(12, 0)")
    }

    @Test
    fun `kotlinx datetime TimeZone parameter emits UTC`() {
        val generated = compileOne(
            paramSignature = "zone: kotlinx.datetime.TimeZone",
            stubs = listOf(kotlinxDatetimeStub),
        )
        assertThat(generated).contains("zone = TimeZone.UTC")
    }

    @Test
    fun `OffsetDateTime parameter emits a fixed UTC timestamp`() {
        val generated = compileOne(
            paramSignature = "at: java.time.OffsetDateTime",
            stubs = emptyList(),
        )
        assertThat(generated).contains("at = OffsetDateTime.of(2024, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)")
    }

    @Test
    fun `BigDecimal parameter emits ZERO`() {
        val generated = compileOne(
            paramSignature = "price: java.math.BigDecimal",
            stubs = emptyList(),
        )
        assertThat(generated).contains("price = BigDecimal.ZERO")
    }

    @Test
    fun `BigInteger parameter emits ZERO`() {
        val generated = compileOne(
            paramSignature = "count: java.math.BigInteger",
            stubs = emptyList(),
        )
        assertThat(generated).contains("count = BigInteger.ZERO")
    }

    @Test
    fun `ImmutableList of String emits persistentListOf sized by collectionSize`() {
        val generated = compileOne(
            paramSignature = "tags: kotlinx.collections.immutable.ImmutableList<String>",
            stubs = listOf(immutableCollectionsStub),
        )
        assertThat(generated).contains("tags = persistentListOf(\"Sample\", \"Sample\")")
    }

    @Test
    fun `PersistentSet of Int emits persistentSetOf`() {
        val generated = compileOne(
            paramSignature = "ids: kotlinx.collections.immutable.PersistentSet<Int>",
            stubs = listOf(immutableCollectionsStub),
        )
        assertThat(generated).contains("ids = persistentSetOf(0)")
    }

    @Test
    fun `ImmutableMap of String to Int emits persistentMapOf`() {
        val generated = compileOne(
            paramSignature = "scores: kotlinx.collections.immutable.ImmutableMap<String, Int>",
            stubs = listOf(immutableCollectionsStub),
        )
        assertThat(generated).contains("scores = persistentMapOf(\"Key\" to 0)")
    }

    @Test
    fun `Coil3 AsyncImagePainter parameter is refused as PG003`() {
        val source = SourceFile.kotlin(
            "Target.kt",
            """
            package sample

            import androidx.compose.runtime.Composable
            import io.github.akshaychordiya.pose.Pose

            @Composable
            @Pose
            fun Target(painter: coil3.compose.AsyncImagePainter) { }
            """.trimIndent()
        )
        val result = CompileHarness.compile(listOf(source, coil3Stub))
        assertThat(result.messages).contains("PG003")
        assertThat(result.messages).contains("AsyncImagePainter")
    }

    // ---- Stubs for the new entries ----

    private val modifierStub = SourceFile.kotlin(
        "ModifierStub.kt",
        """
        package androidx.compose.ui
        interface Modifier { companion object : Modifier }
        """.trimIndent()
    )

    private val shapeStub = SourceFile.kotlin(
        "ShapeStub.kt",
        """
        package androidx.compose.ui.graphics
        interface Shape
        val RectangleShape: Shape = object : Shape {}
        """.trimIndent()
    )

    private val brushStub = SourceFile.kotlin(
        "BrushStub.kt",
        """
        package androidx.compose.ui.graphics
        abstract class Brush
        class SolidColor(val value: Color) : Brush()
        """.trimIndent()
    )

    private val textStyleStub = SourceFile.kotlin(
        "TextStyleStub.kt",
        """
        package androidx.compose.ui.text
        class TextStyle { companion object { val Default: TextStyle = TextStyle() } }
        """.trimIndent()
    )

    private val kotlinxDatetimeStub = SourceFile.kotlin(
        "KotlinxDatetimeStub.kt",
        """
        package kotlinx.datetime
        class LocalTime(val hour: Int, val minute: Int)
        class TimeZone private constructor() { companion object { val UTC: TimeZone = TimeZone() } }
        """.trimIndent()
    )

    private val immutableCollectionsStub = SourceFile.kotlin(
        "ImmutableCollectionsStub.kt",
        """
        package kotlinx.collections.immutable

        interface ImmutableCollection<out E> : Collection<E>
        interface ImmutableList<out E> : List<E>, ImmutableCollection<E>
        interface PersistentList<out E> : ImmutableList<E>
        interface ImmutableSet<out E> : Set<E>, ImmutableCollection<E>
        interface PersistentSet<out E> : ImmutableSet<E>
        interface ImmutableMap<K, out V> : Map<K, V>
        interface PersistentMap<K, out V> : ImmutableMap<K, V>

        private class PListImpl<E>(list: List<E>) : PersistentList<E>, List<E> by list
        private class PSetImpl<E>(set: Set<E>) : PersistentSet<E>, Set<E> by set
        private class PMapImpl<K, V>(map: Map<K, V>) : PersistentMap<K, V>, Map<K, V> by map

        fun <E> persistentListOf(vararg elements: E): PersistentList<E> = PListImpl(elements.toList())
        fun <E> persistentSetOf(vararg elements: E): PersistentSet<E> = PSetImpl(elements.toSet())
        fun <K, V> persistentMapOf(vararg pairs: Pair<K, V>): PersistentMap<K, V> = PMapImpl(pairs.toMap())
        """.trimIndent()
    )

    private val coil3Stub = SourceFile.kotlin(
        "Coil3Stub.kt",
        """
        package coil3.compose
        class AsyncImagePainter
        """.trimIndent()
    )
}
