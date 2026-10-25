import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, libs.versions.intellij.target.get())
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
}

intellijPlatform {
    pluginConfiguration {
        id.set("io.github.akshaychordiya.pose")
        name.set("Pose - Auto generate Compose Previews")
        version.set(project.version.toString())
        vendor {
            name.set("Akshay Chordiya")
            url.set("https://github.com/AkshayChordiya/Pose")
        }
        // Marketplace shows this HTML verbatim on the plugin page.
        description.set(
            """
            <h2>Pose - Auto generate Compose Previews</h2>

            <p><b>Pose</b> is a KSP2 processor that auto-generates Jetpack Compose <code>@Preview</code>
            functions from your <code>@Composable</code> code at build time - deterministic,
            offline, no LLM, never touches your source. Every build regenerates the previews
            into <code>build/generated/ksp/</code>.</p>

            <p>This IntelliJ plugin adds the IDE affordances on top of the KSP processor:</p>

            <ul>
              <li><b>Gutter icons</b> next to every composable that Pose generates a preview for — both
                explicit <code>@Pose</code>-annotated composables <em>and</em> bulk-mode composables
                that Pose picks up automatically.</li>
              <li><b>Click to jump</b> straight to the matching <code>&lt;Composable&gt;__Preview*</code>
                function in the generated file - no more digging through <code>build/generated/</code>.</li>
              <li><b>Popup chooser</b> for sealed fan-outs - pick <code>_Loading</code> /
                <code>_Success</code> / <code>_Error</code> and land there directly.</li>
            </ul>

            <h3>Requires</h3>
            <ul>
              <li>Android Studio Ladybug (2024.2) or newer, or the equivalent IntelliJ IDEA build</li>
              <li>Kotlin plugin in K1 or K2 mode - both supported</li>
              <li>The Pose KSP processor added to your build dependency</li>
            </ul>

            <p>See <a href="https://github.com/AkshayChordiya/Pose">github.com/AkshayChordiya/Pose</a>
            for full documentation, the sample app, and Compose snapshot-testing
            recipes (Paparazzi, Roborazzi, Google's screenshot plugin).</p>

            <p><b>Optional but recommended.</b> The KSP processor works standalone - the plugin
            just removes the need to open <code>build/generated/</code> by hand.</p>
            """.trimIndent()
        )
        // Marketplace's "What's New" tab — historical entries stack top-down so
        // returning users see what's new since the version they installed.
        changeNotes.set(
            """
            <h3>0.6.5</h3>
            <ul>
              <li><b>Bazel and custom project layout support:</b> Gutter icons now resolve
                  previews in Bazel projects (under <code>bazel-bin/</code> or
                  <code>bazel-out/</code>) and fall back to IDE-indexed files.</li>
              <li>Compatible with Pose 0.6.5 KSP processor and expanded standard type synthesis.</li>
            </ul>

            <h3>0.5.0</h3>
            <ul>
              <li>KSP side: generated previews now provide
                  <code>LocalInspectionMode = true</code>, so composables that branch on it
                  render their preview path in snapshot tests (Paparazzi / Roborazzi) the
                  same way they already do in Studio's preview panel.</li>
              <li>KSP side: new <code>pose.previewWrapperFqName</code> option for supplying
                  arbitrary <code>CompositionLocal</code>s to every generated preview.</li>
              <li>Plugin binary unchanged from 0.4.3.</li>
            </ul>

            <h3>0.4.3</h3>
            <ul>
              <li><b>KMP / CMP support for gutter icons.</b> The generated-file locator now
                  walks nested KSP output layouts, so composables declared in
                  <code>commonMain</code> (and any other Kotlin Multiplatform source set)
                  get their gutter icon. Previously only Android's flat
                  <code>build/generated/ksp/&lt;variant&gt;/kotlin/</code> layout was found;
                  KMP nests one level deeper as
                  <code>build/generated/ksp/&lt;target&gt;/&lt;sourceSet&gt;/kotlin/</code>.</li>
            </ul>

            <h3>0.4.2</h3>
            <ul>
              <li>KSP side: bulk mode now auto-skips the theme composable identified
                  by <code>pose.themeFqName</code> - no more <code>@PoseIgnore</code>
                  boilerplate on your <code>AppTheme</code>.</li>
              <li>Verified working on Compose Multiplatform / Kotlin Multiplatform
                  projects - wire the processor into your <code>ksp&lt;Target&gt;Main</code>
                  configuration.</li>
              <li>Plugin binary unchanged from 0.4.1.</li>
            </ul>

            <h3>0.4.1</h3>
            <ul>
              <li>Clearer gutter tooltip - <code>Open Pose preview: &lt;name&gt;</code>
                  instead of just <code>Open &lt;name&gt;</code>, so the click affordance
                  matches the icon's Pose branding.</li>
              <li>Internal: added unit test coverage for the line marker provider
                  (no-file / single-match / sealed-fan-out / stale-file scenarios).</li>
            </ul>

            <h3>0.4.0 - first Marketplace release</h3>
            <ul>
              <li>Gutter icon next to every composable that has a Pose-generated preview</li>
              <li>Click to jump straight to the corresponding <code>__Preview*</code> function
                  in the generated file</li>
              <li>Popup chooser for sealed fan-outs — one entry per subtype</li>
              <li>Works with both explicit <code>@Pose</code> annotations and bulk mode
                  (<code>pose.generatePreviewsForAllPublicComposables</code>)</li>
              <li>K1 and K2 Kotlin plugin modes both supported</li>
              <li>Icon set built around the Material Symbols <em>undereye</em> glyph</li>
            </ul>
            """.trimIndent()
        )
        ideaVersion {
            sinceBuild.set("242")
            untilBuild.set(provider { null })
        }
    }

    // Verifies the plugin against JetBrains' recommended IDE builds — catches API
    // regressions before submitting to Marketplace. Run explicitly with:
    //     ./gradlew :intellij-plugin:verifyPlugin
    // (Downloads several hundred MB of IDE distributions; skips otherwise.)
    pluginVerification {
        ides {
            recommended()
        }
    }

    // Local invocation: MARKETPLACE_TOKEN=xxx ./gradlew :intellij-plugin:publishPlugin
    // CI: .github/workflows/plugin-release.yml fires on `plugin-v*` tags or manual
    // workflow_dispatch — deliberately decoupled from the KSP release cadence.
    publishing {
        token.set(providers.environmentVariable("MARKETPLACE_TOKEN"))
        // Channel is overrideable via the workflow_dispatch input (default / beta / eap)
        // — falls back to "default" (public stable) if unset.
        channels.set(
            providers.gradleProperty("intellijPlatformPublishingChannel")
                .map { listOf(it) }
                .orElse(listOf("default"))
        )
    }
}
