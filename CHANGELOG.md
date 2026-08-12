# Changelog

All notable changes to Pose are documented here. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic versioning.

## [Unreleased] - Keep the theme through incremental builds

### Fixed

- **Editing a composable no longer strips the theme from every preview in the module.**

  `getSymbolsWithAnnotation` only returns symbols from KSP's dirty file set. Nothing in
  your source connects a composable to the module's `@PoseSetup` object - that link only
  exists in the code Pose emits, as `AppPose.Theme { … }` - so on any incremental round
  that didn't happen to touch the config file, Pose couldn't see it. It took the
  no-config path: warned `PG024` and regenerated every preview **unthemed**, and they
  stayed that way until the next clean build. Same symptom as 0.6.1's missing config
  object, except nothing was missing.

  Generated files now name the setup object's file as a KSP `Dependencies` source, which
  keeps it in the dirty set. Previews stay isolating (`aggregating = false`), so
  incremental builds cost the same as before.

  Only the config object needs this. Parameter types, sealed subtypes and
  `PreviewParameterProvider` classes are all named somewhere in your own source, so
  Kotlin already dirties the composable when they change.

## [0.6.2] - Don't build preview data in production

### Changed

- **`PG001` and `PG010` now suggest `get() = sequenceOf(...)`** rather than a plain `val`. `sequenceOf(a, b, c)` evaluates its arguments eagerly — a `Sequence` is lazy about *iteration*, not construction — so the previously-suggested form built every sample when the companion object initialised. That happens the first time anything touches the type, **in production**, for data only the preview panel reads. The getter defers it entirely, and release builds never call it since generated previews live in the debug variant.

  Same correction applied to the README and [refusal catalog](docs/refusals.md#pg001), both of which now explain why rather than just showing the corrected code.

  Thanks to feedback on the 0.6.x docs for catching this.

### Docs

- README leads with a demo GIF.
- Documents putting a `PreviewParameterProvider` in `src/debug/kotlin` as the stronger option when you'd rather keep sample data out of production source altogether.

## [0.6.1] - Tell people when the theme is missing

Upgrading to 0.6.0 removed `pose.themeFqName`. If you didn't also add a `@PoseSetup` object, Pose carried on generating previews with **no theme** and said nothing - they render in Compose's baseline palette, which looks like a Pose bug rather than missing configuration. Snapshot suites see it as a wall of unexplained golden diffs.

No behavior changes; this release is entirely about making that state visible.

### Added

- **`PG024`** - warns when a module generates previews but has no `@PoseSetup` object, naming the exact config to add:

  ```
  [PG024] generated 14 preview(s) in this module but found no @PoseSetup object,
  so they render with Compose's default theme rather than yours. Add:
      @PoseSetup
      internal object AppPose : PoseConfig {
          @Composable
          override fun Theme(content: @Composable () -> Unit) = AppTheme(content)
      }
  ```

  Always a warning, never an error - generation succeeded, it just may not look right. Unthemed previews are legitimate for a design-system module, so declaring `@PoseSetup internal object AppPose : PoseConfig` with no `Theme` override is the explicit opt-out and silences it. Not governed by `pose.strict`, because bulk mode forces `strict = false` and that's exactly where losing a whole module's theme hurts most.

### Changed

- **`PG023` now names the migration** for each option removed in 0.6.0 instead of just reporting it as unrecognised:

  ```
  [PG023] `pose.themeFqName` was removed in 0.6.0 and is being ignored —
  previews will render UNTHEMED until you add a @PoseSetup object overriding `Theme`.
  ```

## [0.6.0] - Type-safe configuration

Configuration moves out of Gradle strings and into Kotlin for type-safety. **Breaking** - the `pose.*` options it replaces are removed rather than deprecated; see Removed below for the one-block migration.

### Added

- **`@PoseSetup` + `PoseConfig`** - one config object per module:

  ```kotlin
  @PoseSetup(generateForAllPublicComposables = true)
  internal object FeaturePose : PoseConfig {
      @Composable
      override fun Theme(content: @Composable () -> Unit) {
          AppTheme { content() }
      }
  }
  ```

  Pose never learns your theme's name - it emits `FeaturePose.Theme { … }`. The reference to `AppTheme` is ordinary Kotlin, so the compiler checks it and the IDE refactors it. Renaming a theme can no longer break previews.
- Scalars (`generateForAllPublicComposables`, `previews`, `provideInspectionMode`, `maxPreviewsPerComposable`, `maxDepth`, `collectionSize`) live on the `@PoseSetup` annotation rather than as interface properties, because KSP can read annotation arguments but not property initializers.
- Shared config across modules via ordinary inheritance - put overrides on an `abstract class` in your design-system module, extend it per module.
- `PG023` flags misspelled `pose.*` keys with a "did you mean" suggestion. Previously a typo silently took the default.
- `PG019`–`PG022` cover config-object misuse: duplicate setup objects, `@PoseSetup` on a non-object, an unreachable (`private`) object, and one that doesn't implement `PoseConfig`.

### Changed

- **Bulk mode now skips wrapper-shaped composables** - any composable whose required parameters are all `@Composable` content lambdas. Themes, surfaces and providers render nothing but synthesized empty content, so they were never useful previews. This replaces the 0.4.2 "skip the composable matching `pose.themeFqName`" rule, which can't work once the theme is invoked inside an override body KSP can't see.

- **`@PoseSample` replaces `PoseProvider(…, forParam = "…")`.** Binding a provider to one specific parameter now happens *on* that parameter:

  ```kotlin
  @Pose
  @Composable
  fun ArticleComparison(
      @PoseSample(ArticleSamples::class)         left: Article,
      @PoseSample(FeaturedArticleSamples::class) right: Article,
  ) { … }
  ```

  `forParam` named a parameter by string from a distance — exactly the failure mode this release removes everywhere else. Renaming the parameter silently rebound it to the wrong provider (via generic-type fallback) or dropped it to structural synthesis. The annotation travels with the declaration, so there's nothing to desync.

### Removed

- **`PoseProvider` is gone.** Without `forParam` it was a single-field wrapper around a `KClass`, so `providers` takes classes directly: `@Pose(providers = [ArticleSamples::class])` instead of `@Pose(providers = [PoseProvider(ArticleSamples::class)])`.

**Breaking.** Every `pose.*` KSP option that shapes previews is gone - `@PoseSetup` replaces all of them, and keeping two ways to configure the same thing wasn't worth it at 0.x. Passing a removed key now warns via `PG023` rather than being silently ignored.

| Removed                                        | Replacement                                          |
|------------------------------------------------|------------------------------------------------------|
| `pose.themeFqName`                             | `override fun Theme(content)` on your `PoseConfig`   |
| `pose.previewWrapperFqName`                    | `override fun Wrapper(content)`                      |
| `pose.generatePreviewsForAllPublicComposables` | `@PoseSetup(generateForAllPublicComposables = true)` |
| `pose.provideInspectionMode`                   | `@PoseSetup(provideInspectionMode = …)`              |
| `pose.maxPreviewsPerComposable`                | `@PoseSetup(maxPreviewsPerComposable = …)`           |
| `pose.maxDepth`                                | `@PoseSetup(maxDepth = …)`                           |
| `pose.collectionSize`                          | `@PoseSetup(collectionSize = …)`                     |

`pose.strict`, `pose.verboseSkips` survive - they're build-behavior knobs with no annotation equivalent, and you may want them to differ between a local build and CI.

`PG011` and `PG018` retire with the string options they validated; `PoseConfig.Theme` / `.Wrapper` are compiler-checked, so there's nothing left to validate at build time.

**Migration** replace the `ksp { arg(...) }` block with a config object:

```kotlin
// Before
ksp {
    arg("pose.themeFqName", "com.example.ui.AppTheme")
    arg("pose.generatePreviewsForAllPublicComposables", "true")
}

// After
@PoseSetup(generateForAllPublicComposables = true)
internal object AppPose : PoseConfig {
    @Composable
    override fun Theme(content: @Composable () -> Unit) = AppTheme(content)
}
```
- The `annotations` artifact now applies the Compose compiler plugin and takes `compose-runtime` as `compileOnly`, so `PoseConfig` can declare `@Composable` members. **The published POM is unchanged** — `compileOnly` doesn't publish, so the artifact still has no Compose dependency and the marker annotations stay usable without Compose on the classpath.

### Fixed

- Bulk mode enabled via `@PoseSetup` now correctly coerces `strict` to `false`. `Diagnostics` was built from pre-merge options, so the coercion was skipped when bulk arrived from the config object rather than a Gradle arg.

### Unchanged

- **The IntelliJ plugin.** It locates generated files by path and function name, neither of which changed. No plugin release needed.

## [0.5.0] - LocalInspectionMode + custom CompositionLocals

### Added

- **Generated previews now provide `LocalInspectionMode = true`.** Every generated preview is wrapped in `CompositionLocalProvider(LocalInspectionMode provides true)`. Studio's preview renderer already sets this, but snapshot runners (Paparazzi, Roborazzi, `com.android.compose.screenshot`) leave it `false` — so a composable branching on `LocalInspectionMode.current` took its production path there, attempting real network calls with dummy URLs and producing blank snapshots. Opt out with `pose.provideInspectionMode = false`.
- **`pose.previewWrapperFqName`** - point at your own composable to supply arbitrary `CompositionLocal`s (fake image loaders, no-op analytics, locale providers). Same trailing-lambda contract as `pose.themeFqName`. Nesting, outermost first: inspection-mode provider → your wrapper → theme → target. The wrapper sits inside Pose's provider so it keeps the final say over any local Pose also sets.

### Changed

- **Generated output changes for every user** - the extra `CompositionLocalProvider` layer appears in all generated previews. Behaviourally a no-op in Studio (which already sets the local); the difference shows up in snapshot tests, which is the point. No action needed unless you were relying on snapshot tests exercising the non-inspection path — in that case set `pose.provideInspectionMode = false`.
- Emission is skipped silently when `androidx.compose.ui.platform.LocalInspectionMode` isn't on the compiled classpath (a module with `compose-runtime` but no `compose-ui`), so no build can break on the new reference.

### Tests

- New `InspectionModeTest` - 6 cases covering default-on, opt-out, nesting order vs the theme, wrapper emission, full four-layer nesting order, and the PG018 refusal.
- 4 new `OptionsTest` cases for the two new options. 60 processor tests total.

## [0.4.3] - KMP gutter icons

Plugin-only release; no KSP artifact changes.

### Fixed

- **Gutter icons now appear for KMP / CMP composables.** The IntelliJ plugin's generated-file locator only handled Android's flat `build/generated/ksp/<variant>/kotlin/…` layout. Kotlin Multiplatform nests one level deeper — `build/generated/ksp/<target>/<sourceSet>/kotlin/…` (e.g. `metadata/commonMain/kotlin/…`) — so composables declared in `commonMain` had no icon. Replaced the single-level scan with a bounded BFS that covers both layouts.
- New `PoseLineMarkerProviderTest` case locks in the `metadata/commonMain` path.

## [0.4.2] - Theme auto-skip + CMP/KMP verified

### Added

- **Auto-skip the theme composable in bulk mode.** When `pose.generatePreviewsForAllPublicComposables = true`, the composable whose FQN matches `pose.themeFqName` is now skipped automatically — no more `@PoseIgnore` boilerplate on `AppTheme`. Reason: previewing the theme just renders its empty content lambda.
- IDE plugin now available to make it easy to quickly see the generated previews
- New `BulkOptInTest` case locks in the theme-auto-skip behaviour.

### Verified

- Pose runs cleanly on **Compose Multiplatform / Kotlin Multiplatform** projects. `androidx.compose.ui.tooling.preview.Preview` is now the shared annotation FQN across Android and CMP; the emission works on both. Wire the processor into `ksp<Target>Main` in the target module's `build.gradle.kts`.

### Docs

- README's "Limitations" section replaces "Compose Multiplatform — Android-only for v1" with a new "Platforms 🌍" section listing Android and KMP/CMP as first-class.

## [0.4.1] - IntelliJ plugin polish

Plugin-only release; no KSP artifact changes. Published to JetBrains Marketplace via the new `plugin-v*` tag flow (KSP artifacts on Central stay at 0.4.0).

### Changed

- Gutter-icon tooltip now reads `Open Pose preview: <name>` instead of just `Open <name>` - the click affordance matches the icon's Pose branding.

### Added

- 4 `BasePlatformTestCase` unit tests for `PoseLineMarkerProvider` covering: no gutter without a generated file, one target when a matching preview exists, popup-count tooltip for sealed fan-outs, no gutter when the generated file exists but has no matching function name.
- New `.github/workflows/plugin-release.yml` - the plugin now releases independently of KSP artifacts (triggers on `plugin-v*` tag push or manual `workflow_dispatch` with a channel input).
- `.run/Build Plugin.run.xml` - shared Gradle run configuration; `Build Plugin` shows up in the IDE run-config dropdown.
- `PUBLISHING.md` documents the plugin release cadence, Marketplace token setup, tag convention, ad hoc dispatch, and troubleshooting.

## [0.4.0] - first Maven Central release

First release published to Maven Central under `io.github.akshaychordiya.pose`. No API changes from 0.3.0 - this cut is about getting the artifact into people's hands.

### Publishing

- POM metadata (name, description, url, licenses, developers, scm) wired on both artifacts so Central Portal accepts the upload.
- GitHub Actions release workflow triggers on `v*` tag push and runs `publishAndReleaseToMavenCentral` (auto-promotes the staging repo).
- Signing keys and Sonatype credentials sourced from repo secrets - no local key material required for CI.

## [0.3.0] - bulk opt-in + better refusal messages

### Added

- `pose.generatePreviewsForAllPublicComposables` KSP option. When `true`, every public top-level `@Composable fun … : Unit` in the module gets a generated preview without needing `@Pose`. Skips composables that already carry `@Pose` (explicit config wins), `@PoseIgnore`, or a hand-written `@Preview`.
- `@PoseIgnore` annotation for opting a single composable out of bulk mode.
- `docs/refusals.md` - catalog of every `PG-xxx` code with rewrite patterns. All diagnostic messages link back here via an anchor.

### Changed

- Bulk mode implicitly coerces `pose.strict` to `false`; a single un-fakeable composable becomes a warning-and-skip instead of failing the build.
- `Diagnostics.refusal()` now demotes every refusal code to a warning when `strict = false`. Previously only PG001/PG002/PG010 were downgradable, so `strict = false` was misleadingly lenient for signature refusals.
- SignatureChecker refusals routed through `refusal()` instead of `hardError()` so `pose.strict` actually applies to them.
- All refusal messages now name the composable, give a concrete fix (rewrite skeleton, provider snippet, or config change), and link to `docs/refusals.md#<pg-code>`.

## [0.2.0] - `@Pose(providers)` + capability showcase

### Added

- `@Pose(providers = [PoseProvider(...)])` binds a `PreviewParameterProvider<T>` at the composable level. Two matching modes: generic-type (default) and explicit `forParam` for disambiguating same-typed parameters. Named binding wins over generic-type binding.
- Default `@Preview` pair now stamps `showBackground = true` so previews read correctly against dark IDE themes.

### Fixed

- Provider-slot selection now skips parameters already bound by `@Pose(providers)`, so explicit providers beat structural / companion / sealed fan-out for their parameter.
- `SampleResolver` refuses to synthesize class-shaped types whose visibility is not `public` or `internal` - was previously emitting references to private data classes that failed to compile in downstream projects.

### Removed

- Pre-release `@PreviewSampleOf` - the surface consolidated onto `@Pose(providers)`.

## [0.1.0] - initial

- `@Pose` annotation, KSP2 processor, tier ladder (T0 default → T1 well-known FQN → T2 structural synthesis).
- Sealed fan-out (one preview per subtype), `companion.previewSamples` support, theme wrapping via `pose.themeFqName`.
- Sample app with LoginContent + HomeContent.

[0.6.2]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.6.2
[0.6.1]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.6.1
[0.6.0]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.6.0
[0.5.0]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.5.0
[0.4.3]: https://github.com/AkshayChordiya/Pose/releases/tag/plugin-v0.4.3
[0.4.2]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.4.2
[0.4.1]: https://github.com/AkshayChordiya/Pose/releases/tag/plugin-v0.4.1
[0.4.0]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.4.0
[0.3.0]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.3.0
[0.2.0]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.2.0
[0.1.0]: https://github.com/AkshayChordiya/Pose/releases/tag/v0.1.0
