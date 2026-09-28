package com.codingfeline.buildkonfig.compiler

import com.codingfeline.buildkonfig.compiler.generator.BuildKonfigCompiler
import com.codingfeline.buildkonfig.compiler.generator.FileAppender
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Replays the full generation pipeline — common object first, then each target in a
 * fixed order — with minimal real objects (real compiler, real files on disk). One
 * step is made to fail in the middle; the test asserts invocation counts, the exact
 * remaining state after the failure, and the recovery once the cause is removed.
 */
class BuildKonfigEnvironmentReplayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `compile sequence replays success, blocked and retried steps in fixed order`() {
        val root = tempFolder.newFolder("out")
        val appender = CountingAppender(root, failOnceFor = "iosX64Main")

        val common = configFile("commonMain", PlatformType.common, File(root, "commonMain"), "env" to "default")
        val jvm = configFile("jvmMain", PlatformType.jvm, File(root, "jvmMain"), "env" to "jvm")
        val iosX64 = configFile("iosX64Main", PlatformType.native, File(root, "iosX64Main"), "env" to "iosX64")
        val iosArm64 = configFile("iosArm64Main", PlatformType.native, File(root, "iosArm64Main"), "env" to "iosArm64")

        // Fixed order: common -> jvm -> iosX64 (blocked) -> iosArm64 -> iosX64 (retried).
        BuildKonfigCompiler.compileCommon(PACKAGE_NAME, OBJECT_NAME, false, common, appender.fn, NoOpLogger)
        BuildKonfigCompiler.compileTarget(PACKAGE_NAME, OBJECT_NAME, false, jvm, appender.fn, NoOpLogger)
        val blocked = runCatching {
            BuildKonfigCompiler.compileTarget(PACKAGE_NAME, OBJECT_NAME, false, iosX64, appender.fn, NoOpLogger)
        }
        BuildKonfigCompiler.compileTarget(PACKAGE_NAME, OBJECT_NAME, false, iosArm64, appender.fn, NoOpLogger)

        assertThat(blocked.exceptionOrNull()).isInstanceOf(IOException::class.java)

        // The blocked step was attempted exactly once and the sequence continued in order.
        assertThat(appender.requestedFiles).containsExactly(
            "commonMain/com/example/BuildKonfig.kt",
            "jvmMain/com/example/BuildKonfig.kt",
            "iosX64Main/com/example/BuildKonfig.kt",
            "iosArm64Main/com/example/BuildKonfig.kt",
        ).inOrder()

        // Remaining state after the failure: every step but the blocked one produced its file.
        assertThat(File(root, "commonMain/com/example/BuildKonfig.kt").isFile).isTrue()
        assertThat(File(root, "jvmMain/com/example/BuildKonfig.kt").isFile).isTrue()
        assertThat(File(root, "iosX64Main/com/example/BuildKonfig.kt").exists()).isFalse()
        assertThat(File(root, "iosArm64Main/com/example/BuildKonfig.kt").isFile).isTrue()

        // Retry the blocked step with the same appender: the injected failure is spent.
        BuildKonfigCompiler.compileTarget(PACKAGE_NAME, OBJECT_NAME, false, iosX64, appender.fn, NoOpLogger)

        assertThat(appender.requestedFiles).hasSize(5)
        assertThat(File(root, "iosX64Main/com/example/BuildKonfig.kt").readText())
            .contains("actual val env: String = \"iosX64\"")
    }

    @Test
    fun `generateConfigs reports the failed target, completes the rest, and recovers on retry`() {
        val commonDir = tempFolder.newFolder("commonMain")
        val jvmDir = tempFolder.newFolder("jvmMain")
        val iosArm64Dir = tempFolder.newFolder("iosArm64Main")
        // Blocked step: a regular file sits where the target's output directory should be,
        // so the writer cannot create "<file>/com/example/BuildKonfig.kt".
        val blockedOutput = tempFolder.newFile("iosX64Main")

        fun data(iosX64Output: File) = BuildKonfigData(
            packageName = PACKAGE_NAME,
            objectName = OBJECT_NAME,
            exposeObject = false,
            commonConfig = configFile("commonMain", PlatformType.common, commonDir, "env" to "default"),
            targetConfigs = listOf(
                configFile("jvmMain", PlatformType.jvm, jvmDir, "env" to "jvm"),
                configFile("iosX64Main", PlatformType.native, iosX64Output, "env" to "iosX64"),
                configFile("iosArm64Main", PlatformType.native, iosArm64Dir, "env" to "iosArm64"),
            ),
            hasJsTarget = false,
        )

        val first = BuildKonfigEnvironment(data(blockedOutput))
            .generateConfigs(NoOpLogger)

        assertThat(first).isInstanceOf(BuildKonfigEnvironment.CompilationStatus.Failure::class.java)
        val errors = (first as BuildKonfigEnvironment.CompilationStatus.Failure).errors
        assertThat(errors).hasSize(1)
        // The exact wording is platform-dependent ("Not a directory" on POSIX); only
        // require that the failure is surfaced as a non-blank message.
        assertThat(errors.single()).isNotEmpty()

        // Final remaining state: the failure did not stop the targets after the blocked one.
        assertThat(File(commonDir, "com/example/BuildKonfig.kt").isFile).isTrue()
        assertThat(File(jvmDir, "com/example/BuildKonfig.kt").readText())
            .contains("actual val env: String = \"jvm\"")
        assertThat(File(iosArm64Dir, "com/example/BuildKonfig.kt").readText())
            .contains("actual val env: String = \"iosArm64\"")

        // Recovery: remove the cause and re-run from the same inputs.
        assertThat(blockedOutput.delete()).isTrue()
        val iosX64Dir = File(blockedOutput.parentFile, "iosX64Main").apply { mkdirs() }

        val second = BuildKonfigEnvironment(data(iosX64Dir))
            .generateConfigs(NoOpLogger)

        assertThat(second).isEqualTo(BuildKonfigEnvironment.CompilationStatus.Success)
        assertThat(File(iosX64Dir, "com/example/BuildKonfig.kt").readText())
            .contains("actual val env: String = \"iosX64\"")
    }

    /**
     * Records every requested file (relative to [root], so assertions stay independent of
     * the random temp path) and throws [IOException] the first time a path containing
     * [failOnceFor] is requested.
     */
    private class CountingAppender(root: File, private val failOnceFor: String) {
        private val rootPath = root.absolutePath
        val requestedFiles = mutableListOf<String>()
        private var failureSpent = false

        val fn: FileAppender = { fileName ->
            requestedFiles += fileName.removePrefix("$rootPath/")
            if (!failureSpent && fileName.contains(failOnceFor)) {
                failureSpent = true
                throw IOException("injected failure for $failOnceFor")
            }
            val file = File(fileName)
            file.parentFile.mkdirs()
            file.writer()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.example"
        const val OBJECT_NAME = "BuildKonfig"

        val NoOpLogger = BuildKonfigLogger { _, _ -> }

        fun configFile(
            name: String,
            platformType: PlatformType,
            outputDirectory: File,
            vararg fields: Pair<String, String>,
        ): TargetConfigFile {
            val config = TargetConfig(name)
            fields.forEach { (fieldName, value) ->
                config.fieldSpecs[fieldName] = FieldSpec(FieldSpec.Type.STRING, fieldName, value)
            }
            return object : TargetConfigFile {
                override val targetName = TargetName(name, platformType)
                override val outputDirectory = outputDirectory
                override val config: TargetConfig = config
            }
        }
    }
}
