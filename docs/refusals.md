# Pose refusal catalog

Each `PG-xxx` diagnostic emitted by the processor links here for a longer explanation and remediation pattern. Sections are ordered by code.

Under `pose.strict = true` (the default) every refusal is a compile error. Under `pose.strict = false` - including implicitly, whenever `@PoseSetup(generateForAllPublicComposables = true)` is set — refusals are warnings and Pose skips the composable.

Configuration codes (PG019–PG023) are always errors regardless of `strict`, except PG023 which is always a warning.

---

## PG001 — no fake-data strategy for parameter

Structural synthesis exhausted every tier (T0 default → T1 well-known FQN → T2 structural walk) and still could not produce a value for the parameter. Usually because the type is domain-specific and Pose has no built-in strategy.

**Fix options, in order of ergonomics:**

1. Add a `companion.previewSamples` sequence on the type:
   ```kotlin
   data class Article(val title: String, val body: String) {
       companion object {
           val previewSamples: Sequence<Article> get() = sequenceOf(Article("Hello", "…"))
       }
   }
   ```

   Use `get()` — `sequenceOf(...)` evaluates its arguments eagerly, so a plain `val` constructs every sample when the companion initialises, in production, for data only previews read.
2. Attach a `PreviewParameterProvider<T>` via `@Pose(providers = [...])`:
   ```kotlin
   class ArticleSamples : PreviewParameterProvider<Article> {
       override val values = sequenceOf(Article("Hello", "…"))
   }
   @Pose(providers = [ArticleSamples::class])
   ```
3. Hand-write a `@Preview` for the composable — Pose detects it and skips generation.

---

## PG002 — cycle or depth exceeded during structural synthesis

Two types refer to each other (or a type refers to itself) and structural synth would recurse forever. Pose stops at `@PoseSetup(maxDepth = …)` (default 8).

**Fix:** break the cycle by providing one of the types via `@Pose(providers = [...])` or `companion.previewSamples`.

---

## PG003 — parameter type is in the refuse category

The parameter's type is on the refuse list — types that inject runtime state (`ViewModel`, Hilt, `SavedStateHandle`, `NavController`, `NavBackStackEntry`, `Context`/`Activity`/`Fragment`, `Bitmap`, `Channel`, `CoroutineScope`, `AsyncImagePainter` — Coil 2 and Coil 3). These can't be faked at preview time without materially changing the composable's behavior.

**Fix — the "Content" split pattern:**

```kotlin
@Composable
fun Screen(vm: MyViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    ScreenContent(state, onEvent = vm::onEvent)
}

@Composable
@Pose
fun ScreenContent(state: MyState, onEvent: (MyEvent) -> Unit) { /* … */ }
```

The stateful wrapper stays lean; the previewable `Content` variant takes plain state.

---

## PG004 — composable declares generic type parameters

Pose can't decide which concrete type argument to fake.

**Fix:** create a non-generic wrapper for the preview:

```kotlin
@Composable @Pose
fun ListScreenStringPreview() = ListScreen<String>(items = listOf("a", "b"))
```

---

## PG005 — composable declares context receivers or parameters

Kotlin's context-parameter feature is not yet supported by Pose.

**Fix:** move the context'd work behind a helper that the composable calls at runtime, or wait for a follow-up release.

---

## PG006 — sealed subtype invisible in this compilation

A sealed hierarchy defined in a downstream/library module can't be walked at compile time from this module.

**Fix:** provide the values via `@Pose(providers = [...])` or `companion.previewSamples` at the type's declaration site.

---

## PG007 — extension receiver not in the whitelist

Only well-known Compose scopes (`BoxScope`, `RowScope`, `ColumnScope`, `LazyListScope`, etc.) can be materialized in previews.

**Fix:** hand-write a `@Preview` that supplies the receiver, or open a PR adding the scope to the whitelist.

---

## PG008 — composable is a member function

`@Pose` only supports top-level composables or members of a top-level `object`.

**Fix:** move the composable to top-level, or into a top-level `object` container.

---

## PG009 — composable is private

Private composables aren't referenceable from the generated file's package.

**Fix:** raise visibility to `internal` or `public`.

---

## PG010 — total preview count exceeds the configured cap

A sealed fan-out (or nested combination) would emit more previews than `@PoseSetup(maxPreviewsPerComposable = …)` (default 8).

**Fix — pick one:**

1. Narrow with a `companion.previewSamples` sequence
2. Raise the cap: `@PoseSetup(maxPreviewsPerComposable = 16)`
3. Split the sealed hierarchy across multiple composables

---

## PG013 — hand-written `@Preview` detected

Informational — Pose noticed an existing `@Preview` on the same composable and skipped generation.

---

## PG014 — `@Pose` applied to a non-`@Composable` function

`@Pose` only decorates composable functions.

**Fix:** add `@Composable`, or remove `@Pose`.

---

## PG015 — `@Pose` applied to a non-Unit-returning composable

Previews render `Unit`-returning composables.

**Fix:** if the return value isn't needed for the preview, refactor to a `Unit`-returning variant.

---

## PG024 — previews generated without a `@PoseSetup` object

Pose generated previews for this module but found no config object, so nothing supplies your theme - they render in Compose's baseline palette (the purple-ish M3 defaults) rather than your app's colours.

Most often seen right after upgrading to 0.6.0, which removed `pose.themeFqName`. Add the object:

```kotlin
@PoseSetup
internal object AppPose : PoseConfig {
    @Composable
    override fun Theme(content: @Composable () -> Unit) {
        AppTheme { content() }
    }
}
```

**Intentionally unthemed?** Declare the object and skip the `Theme` override - `PoseConfig` defaults to a passthrough, and the explicit declaration silences the warning:

```kotlin
@PoseSetup
internal object AppPose : PoseConfig
```

Always a warning, never an error - generation succeeded, it just may not look like you expect.

---

## PG023 — unknown `pose.*` option

A KSP option starting with `pose.` that Pose doesn't recognise — almost always a typo. Pose reads options by exact key, so an unrecognised one would otherwise silently take its default and leave no trace.

```
[PG023] `pose.stict` is not a Pose option — did you mean `pose.strict`?
```

Emitted as a warning, not an error, so a newer Pose version's options don't break an older processor.

**Fix:** correct the spelling, or drop the option. Consider moving configuration into a [`@PoseSetup` object](#pg022) instead — misspelling a property there is a compile error rather than a silent no-op.

---

## PG022 — `@PoseSetup` object does not implement `PoseConfig`

The annotated object needs to implement `PoseConfig` so Pose knows the `Theme` and `Wrapper` hooks exist.

```kotlin
@PoseSetup(generateForAllPublicComposables = true)
internal object FeaturePose : PoseConfig {
    @Composable
    override fun Theme(content: @Composable () -> Unit) {
        AppTheme { content() }
    }
}
```

Both hooks default to passthrough, so overriding only what you need is fine — an object with no overrides is valid, it just adds no wrapping.

---

## PG021 — `@PoseSetup` object is not reachable

The config object is `private`, so generated previews — which live in a different file — can't call it.

**Fix:** make it `internal` (recommended) or `public`.

---

## PG020 — `@PoseSetup` applied to something that isn't an object

Pose calls the config statically, so it must be an `object`, not a `class` or `interface`.

**Fix:** `internal object FeaturePose : PoseConfig`. To share configuration across modules, put the overrides on an interface in your design-system module and have each module's object implement it:

```kotlin
// :design-system
interface AppPoseDefaults : PoseConfig {
    @Composable
    override fun Theme(content: @Composable () -> Unit) = AppTheme(content)
}

// :feature:checkout
@PoseSetup(generateForAllPublicComposables = true)
internal object CheckoutPose : AppPoseDefaults
```

An `abstract class` works too, if you'd rather — but config is stateless, so an interface avoids the constructor call and lets a module compose several defaults if it needs to.

---

## PG019 — more than one `@PoseSetup` in the module

Pose configuration is per-module, so a module with two config objects is ambiguous.

**Fix:** keep one and share the rest through inheritance (see [PG020](#pg020)). KSP can't enumerate annotated symbols across compiled dependencies, which is why every module needs its own object rather than inheriting one wholesale.

---

## PG017 — `previews[]` entry is not annotated `@Preview`

Every class listed in `@Pose(previews = [...])` must itself be annotated with `@Preview` (directly or transitively via multipreview annotations).

**Fix:** either annotate the referenced class with `@Preview`, or point `previews[]` at a valid multipreview annotation like `PreviewLightDark`.
