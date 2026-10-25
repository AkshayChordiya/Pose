package io.github.akshaychordiya.pose.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * BasePlatformTestCase spins up a light IntelliJ project with our plugin.xml
 * extensions registered. Each test builds a mini file tree under the fixture's
 * temp dir (mirroring a Gradle module layout with `build/generated/ksp/…`) then
 * checks the gutter markers our LineMarkerProvider produces on the source file.
 *
 * We assert on tooltip text — the surest per-provider identifier we control,
 * since `findAllGutters()` returns markers from every registered provider.
 */
class PoseLineMarkerProviderTest : BasePlatformTestCase() {

    fun testNoGutterWhenGeneratedFileAbsent() {
        val src = myFixture.tempDirFixture.createFile(
            "src/main/kotlin/com/example/LoginContent.kt",
            """
            package com.example

            fun LoginContent() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        assertTrue(
            "No Pose gutter expected when the generated file is missing",
            posetTooltips().isEmpty(),
        )
    }

    fun testGutterPresentWhenGeneratedFileHasMatchingPreview() {
        myFixture.tempDirFixture.createFile(
            "build/generated/ksp/debug/kotlin/com/example/LoginContent__Preview.kt",
            """
            package com.example

            internal fun LoginContent__Preview() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "src/main/kotlin/com/example/LoginContent.kt",
            """
            package com.example

            fun LoginContent() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        val tooltips = posetTooltips()
        assertEquals("Expected exactly one Pose gutter icon", 1, tooltips.size)
        assertTrue(
            "Single-target tooltip should reference the specific preview name; got: ${tooltips.single()}",
            tooltips.single().contains("LoginContent__Preview"),
        )
    }

    fun testGutterPresentForKmpCommonMainLayout() {
        // KMP puts KSP output one level deeper: build/generated/ksp/<target>/<sourceSet>/kotlin/…
        // For commonMain composables this is `metadata/commonMain/kotlin/…`. The plugin's BFS
        // walker should still find them.
        myFixture.tempDirFixture.createFile(
            "build/generated/ksp/metadata/commonMain/kotlin/com/example/CommonScreen__Preview.kt",
            """
            package com.example

            internal fun CommonScreen__Preview() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "src/commonMain/kotlin/com/example/CommonScreen.kt",
            """
            package com.example

            fun CommonScreen() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        val tooltips = posetTooltips()
        assertEquals("Expected exactly one Pose gutter icon for the commonMain composable", 1, tooltips.size)
        assertTrue(
            "Tooltip should reference the specific preview; got: ${tooltips.single()}",
            tooltips.single().contains("CommonScreen__Preview"),
        )
    }

    fun testSealedFanOutTooltipReportsMultipleTargets() {
        myFixture.tempDirFixture.createFile(
            "build/generated/ksp/debug/kotlin/com/example/HomeContent__Preview.kt",
            """
            package com.example

            internal fun HomeContent__Preview_Loading() { }
            internal fun HomeContent__Preview_Success() { }
            internal fun HomeContent__Preview_Error() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "src/main/kotlin/com/example/HomeContent.kt",
            """
            package com.example

            fun HomeContent() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        val tooltips = posetTooltips()
        assertEquals("Expected exactly one Pose gutter icon", 1, tooltips.size)
        val tooltip = tooltips.single()
        assertTrue(
            "Multi-target tooltip should mention the count; got: $tooltip",
            tooltip.startsWith("3 Pose previews"),
        )
    }

    fun testNoGutterWhenGeneratedFileHasNoMatchingFunction() {
        // Generated file exists but no function matches `Toolbar__Preview*`.
        myFixture.tempDirFixture.createFile(
            "build/generated/ksp/debug/kotlin/com/example/Toolbar__Preview.kt",
            """
            package com.example

            internal fun SomethingElse() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "src/main/kotlin/com/example/Toolbar.kt",
            """
            package com.example

            fun Toolbar() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        assertTrue(
            "No Pose gutter expected when the generated file lacks a matching function",
            posetTooltips().isEmpty(),
        )
    }

    fun testGutterPresentForBazelLayout() {
        // Bazel workspace with WORKSPACE file and BUILD.bazel package root,
        // and generated files in bazel-bin/<package>/...
        myFixture.tempDirFixture.createFile("WORKSPACE", "")
        myFixture.tempDirFixture.createFile("feature/login/BUILD.bazel", "")
        myFixture.tempDirFixture.createFile(
            "bazel-bin/feature/login/ksp/kotlin/com/example/LoginContent__Preview.kt",
            """
            package com.example

            internal fun LoginContent__Preview() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "feature/login/src/main/kotlin/com/example/LoginContent.kt",
            """
            package com.example

            fun LoginContent() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        val tooltips = posetTooltips()
        assertEquals("Expected exactly one Pose gutter icon for Bazel layout", 1, tooltips.size)
        assertTrue(
            "Tooltip should reference LoginContent__Preview; got: ${tooltips.single()}",
            tooltips.single().contains("LoginContent__Preview"),
        )
    }

    fun testGutterPresentForBazelModuleLayoutWithoutKotlinPrefix() {
        // Bazel workspace with MODULE.bazel where KSP output doesn't have a 'kotlin/' prefix
        myFixture.tempDirFixture.createFile("MODULE.bazel", "")
        myFixture.tempDirFixture.createFile("app/BUILD", "")
        myFixture.tempDirFixture.createFile(
            "bazel-bin/app/ksp/com/example/AppHeader__Preview.kt",
            """
            package com.example

            internal fun AppHeader__Preview() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "app/src/com/example/AppHeader.kt",
            """
            package com.example

            fun AppHeader() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        val tooltips = posetTooltips()
        assertEquals("Expected exactly one Pose gutter icon for Bazel module layout", 1, tooltips.size)
        assertTrue(
            "Tooltip should reference AppHeader__Preview; got: ${tooltips.single()}",
            tooltips.single().contains("AppHeader__Preview"),
        )
    }

    fun testGutterPresentForBazelOutLayout() {
        // Bazel workspace using bazel-out/<arch>/bin/<package>/...
        myFixture.tempDirFixture.createFile("MODULE.bazel", "")
        myFixture.tempDirFixture.createFile("ui/profile/BUILD.bazel", "")
        myFixture.tempDirFixture.createFile(
            "bazel-out/darwin_arm64-fastbuild/bin/ui/profile/ksp/kotlin/com/example/ProfileCard__Preview.kt",
            """
            package com.example

            internal fun ProfileCard__Preview() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "ui/profile/src/main/kotlin/com/example/ProfileCard.kt",
            """
            package com.example

            fun ProfileCard() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        val tooltips = posetTooltips()
        assertEquals("Expected exactly one Pose gutter icon for bazel-out layout", 1, tooltips.size)
        assertTrue(
            "Tooltip should reference ProfileCard__Preview; got: ${tooltips.single()}",
            tooltips.single().contains("ProfileCard__Preview"),
        )
    }

    fun testGutterPresentForIndexedFileFallback() {
        // Generated file located anywhere in project that is indexed by IDE
        myFixture.tempDirFixture.createFile(
            "custom-gen/com/example/CustomScreen__Preview.kt",
            """
            package com.example

            internal fun CustomScreen__Preview() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "src/com/example/CustomScreen.kt",
            """
            package com.example

            fun CustomScreen() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        val tooltips = posetTooltips()
        assertEquals("Expected exactly one Pose gutter icon via indexed fallback", 1, tooltips.size)
        assertTrue(
            "Tooltip should reference CustomScreen__Preview; got: ${tooltips.single()}",
            tooltips.single().contains("CustomScreen__Preview"),
        )
    }

    fun testNoGutterWhenIndexedFallbackPackageMismatches() {
        // Generated file has same filename `__Preview.kt`, but belongs to a different package
        myFixture.tempDirFixture.createFile(
            "custom-gen/com/other/DifferentPkgScreen__Preview.kt",
            """
            package com.other

            internal fun DifferentPkgScreen__Preview() { }
            """.trimIndent(),
        )
        val src = myFixture.tempDirFixture.createFile(
            "src/com/example/DifferentPkgScreen.kt",
            """
            package com.example

            fun DifferentPkgScreen() { }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(src)
        myFixture.doHighlighting()

        assertTrue(
            "No Pose gutter expected when indexed file is in a different package",
            posetTooltips().isEmpty(),
        )
    }

    /** Filters gutter markers to only Pose's — keyed on tooltip content. */
    private fun posetTooltips(): List<String> =
        myFixture.findAllGutters()
            .mapNotNull { it.tooltipText }
            .filter { "Pose" in it }
}
