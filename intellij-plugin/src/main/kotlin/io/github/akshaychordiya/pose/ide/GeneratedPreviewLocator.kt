package io.github.akshaychordiya.pose.ide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Locates the Pose-generated `<Source>__Preview.kt` file for a source composable
 * and enumerates the `internal fun <ComposableName>__Preview*` candidates inside.
 *
 * Mapping rules (mirror the processor's emitter):
 *  - Source `com/foo/Bar.kt` → generated `com/foo/Bar__Preview.kt`
 *  - Generated files live under:
 *      - Gradle: `<module>/build/generated/ksp/kotlin/`
 *      - Bazel: `bazel-bin/<target>/kotlin/` or `bazel-bin/<target>/`
 *      - Or registered in project index via IDE build tool integration
 *  - One source file may yield 1…N preview functions (sealed fan-out)
 *
 * Cheap because it's driven by a file-existence check; expensive PSI parsing
 * only kicks in when the file exists.
 */
internal object GeneratedPreviewLocator {

    private val logger = Logger.getInstance(GeneratedPreviewLocator::class.java)

    fun locate(function: KtNamedFunction): List<GeneratedPreview> {
        val composableName = function.name ?: return emptyList()
        val sourceFile = function.containingKtFile
        val sourceVFile = sourceFile.virtualFile ?: return emptyList()

        val packageDir = sourceFile.packageFqName.asString().replace('.', '/')
        val previewFileName = "${sourceVFile.nameWithoutExtension}__Preview.kt"
        val gradleRelPath = if (packageDir.isEmpty()) previewFileName else "kotlin/$packageDir/$previewFileName"

        val generatedVFile = findGeneratedFileFromGradle(sourceVFile, gradleRelPath)
            ?: findGeneratedFileFromBazel(sourceVFile, packageDir, previewFileName)
            ?: findGeneratedFileFromIndex(function, packageDir, previewFileName)
            ?: run {
                if (logger.isDebugEnabled) {
                    logger.debug("[Pose] no generated preview file found for ${sourceVFile.path}")
                }
                return emptyList()
            }

        val generatedKt = PsiManager.getInstance(function.project).findFile(generatedVFile) as? KtFile
            ?: return emptyList()

        val prefix = "${composableName}__Preview"
        return generatedKt.declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { fn ->
                val name = fn.name ?: return@filter false
                name == prefix || name.startsWith("${prefix}_")
            }
            .map { fn ->
                GeneratedPreview(
                    psi = fn,
                    simpleName = fn.name.orEmpty(),
                    file = generatedVFile,
                )
            }
    }

    /**
     * Fast-path for Gradle: walks up from the source file to find `build/generated/ksp`
     * and performs a bounded BFS to locate the generated file.
     */
    private fun findGeneratedFileFromGradle(
        sourceFile: VirtualFile,
        generatedRelPath: String,
    ): VirtualFile? {
        val moduleRoot = findGradleModuleRoot(sourceFile) ?: return null
        val kspRoot = moduleRoot.findFileByRelativePath("build/generated/ksp") ?: return null

        // Force a light VFS refresh once, freshly-generated files may not be in the cache yet
        if (kspRoot.children.isEmpty()) kspRoot.refresh(false, true)

        return findGeneratedFile(kspRoot, generatedRelPath)
    }

    /**
     * Walks up from the source file until it finds an ancestor with `build/generated/ksp`.
     * More robust than IntelliJ module content roots — those point at `src/main` in a
     * Gradle/AS module, not the module directory itself.
     */
    private fun findGradleModuleRoot(sourceFile: VirtualFile): VirtualFile? {
        var current: VirtualFile? = sourceFile.parent
        while (current != null) {
            if (current.findFileByRelativePath("build/generated/ksp") != null) return current
            current = current.parent
        }
        return null
    }

    /**
     * Fast-path for Bazel: walks up to locate the Bazel workspace root and optional
     * package root (containing `BUILD` or `BUILD.bazel`), then searches under
     * `bazel-bin/` (or `bazel-out/`).
     */
    private fun findGeneratedFileFromBazel(
        sourceFile: VirtualFile,
        packageDir: String,
        previewFileName: String,
    ): VirtualFile? {
        val workspaceRoot = findBazelWorkspaceRoot(sourceFile) ?: return null
        val bazelBin = workspaceRoot.findFileByRelativePath("bazel-bin")
            ?: workspaceRoot.findFileByRelativePath("bazel-out")
            ?: return null

        if (bazelBin.children.isEmpty()) bazelBin.refresh(false, true)

        val packageRoot = findBazelPackageRoot(sourceFile, workspaceRoot)
        val searchRoot = if (packageRoot != null) {
            val relPath = getRelativePath(workspaceRoot, packageRoot)
            if (!relPath.isNullOrEmpty()) {
                bazelBin.findFileByRelativePath(relPath) ?: bazelBin
            } else {
                bazelBin
            }
        } else {
            bazelBin
        }

        val targetRelPath1 = if (packageDir.isEmpty()) "kotlin/$previewFileName" else "kotlin/$packageDir/$previewFileName"
        val targetRelPath2 = if (packageDir.isEmpty()) previewFileName else "$packageDir/$previewFileName"

        return findGeneratedFile(searchRoot, targetRelPath1)
            ?: findGeneratedFile(searchRoot, targetRelPath2)
    }

    private fun findBazelWorkspaceRoot(sourceFile: VirtualFile): VirtualFile? {
        var current: VirtualFile? = sourceFile.parent
        while (current != null) {
            if (current.findChild("MODULE.bazel") != null ||
                current.findChild("WORKSPACE.bazel") != null ||
                current.findChild("WORKSPACE") != null ||
                current.findChild("bazel-bin") != null ||
                current.findChild("bazel-out") != null
            ) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun findBazelPackageRoot(sourceFile: VirtualFile, workspaceRoot: VirtualFile): VirtualFile? {
        var current: VirtualFile? = sourceFile.parent
        while (current != null && current != workspaceRoot) {
            if (current.findChild("BUILD.bazel") != null || current.findChild("BUILD") != null) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun getRelativePath(ancestor: VirtualFile, descendant: VirtualFile): String? {
        if (ancestor == descendant) return ""
        val ancestorPath = ancestor.path.trimEnd('/') + "/"
        if (descendant.path.startsWith(ancestorPath)) {
            return descendant.path.substring(ancestorPath.length)
        }
        return null
    }

    /**
     * Fallback for projects where generated files are indexed in the IDE's Project scope
     * (e.g. by Android Studio Bazel plugins or other project models).
     */
    private fun findGeneratedFileFromIndex(
        function: KtNamedFunction,
        packageDir: String,
        previewFileName: String,
    ): VirtualFile? {
        val project = function.project
        val candidates = FilenameIndex.getVirtualFilesByName(
            previewFileName,
            GlobalSearchScope.projectScope(project),
        )
        if (candidates.isEmpty()) return null

        val expectedSuffix = if (packageDir.isEmpty()) {
            "/$previewFileName"
        } else {
            "/$packageDir/$previewFileName"
        }

        return candidates.firstOrNull { it.path.replace('\\', '/').endsWith(expectedSuffix) }
    }

    /**
     * Bounded BFS under [root] looking for the first descendant that contains
     * [relPath].
     *
     * Depth cap is deliberate to avoid runaway directory walks.
     */
    private fun findGeneratedFile(root: VirtualFile, relPath: String): VirtualFile? {
        val queue = ArrayDeque<Pair<VirtualFile, Int>>().apply { addLast(root to 0) }
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            dir.findFileByRelativePath(relPath)?.let { return it }
            if (depth >= MAX_SEARCH_DEPTH) continue
            dir.children.forEach { child ->
                if (child.isDirectory) queue.addLast(child to depth + 1)
            }
        }
        return null
    }

    private const val MAX_SEARCH_DEPTH = 4
}

/** A single generated `<Composable>__Preview*` function that a source composable maps to. */
internal data class GeneratedPreview(
    val psi: KtNamedFunction,
    val simpleName: String,
    val file: VirtualFile,
)
