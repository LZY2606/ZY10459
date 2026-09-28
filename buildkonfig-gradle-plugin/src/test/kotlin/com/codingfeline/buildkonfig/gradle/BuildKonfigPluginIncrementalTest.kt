package com.codingfeline.buildkonfig.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test
import java.io.File

/**
 * Incremental-build behavior of `generateBuildKonfig`.
 *
 * Cache hits and invalidation are proven through [TaskOutcome] after controlled input
 * changes — never by matching Gradle log wording. Fixtures never use real secrets and
 * never read the user Gradle configuration (see [gradleUserHomeIsolationGuard]).
 */
class BuildKonfigPluginIncrementalTest : BaseGradlePluginTest() {

    private val header = buildFileHeader("kotlin-multiplatform") + "\n" + gradleUserHomeIsolationGuard

    private val kmpTargets = """
        |kotlin {
        |  jvm()
        |  iosX64()
        |}
    """.trimMargin()

    private fun flavoredBuildFile(): String =
        """
        |$header
        |
        |buildkonfig {
        |   packageName = "com.sample"
        |
        |   defaultConfigs {
        |       buildConfigField 'STRING', 'env', 'default'
        |   }
        |   defaultConfigs("dev") {
        |       buildConfigField 'STRING', 'env', 'dev'
        |   }
        |   targetConfigs {
        |       jvm {
        |           buildConfigField 'STRING', 'env', 'jvm'
        |       }
        |   }
        |}
        |
        |$kmpTargets
        """.trimMargin()

    /**
     * Run twice to prove the up-to-date hit, then change exactly one input — the flavor
     * — and prove the task re-runs. A third run with the new input must be up-to-date
     * again, pinning the invalidation to the input change itself.
     */
    @Test
    fun `up-to-date is proven by inputs - changing only the flavor invalidates the task`() {
        buildFile.writeText(flavoredBuildFile())
        val buildDir = projectDir.buildKonfigDir()
        val jvmFile = buildKonfigFile(buildDir, "jvmMain", "com.sample")

        val first = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
        assertThat(first.task(":generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(jvmFile.readText()).contains("actual val env: String = \"jvm\"")

        val second = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
        assertThat(second.task(":generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        // Single input change: the flavor property. The jvm targetConfig has no flavored
        // entry, so jvm still wins there — but the native targets flip to the dev default.
        val third = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "-Pbuildkonfig.flavor=dev", "--stacktrace")
            .build()
        assertThat(third.task(":generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(jvmFile.readText()).contains("actual val env: String = \"jvm\"")
        assertThat(buildKonfigFile(buildDir, "iosX64Main", "com.sample").readText())
            .contains("actual val env: String = \"dev\"")

        val fourth = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "-Pbuildkonfig.flavor=dev", "--stacktrace")
            .build()
        assertThat(fourth.task(":generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    /**
     * Two subprojects, one changed value: only the affected project's generation task
     * may re-run. This proves invalidation is scoped to the task's own declared inputs
     * rather than to anything global (daemon, timestamp, sibling builds).
     */
    @Test
    fun `changing one target value invalidates only the affected subproject task`() {
        settingFile.appendText("\ninclude ':app', ':lib'\n")

        fun subprojectBuildFile(name: String, value: String) = """
            |$header
            |
            |buildkonfig {
            |   packageName = "com.sample.$name"
            |
            |   defaultConfigs {
            |       buildConfigField 'STRING', 'env', 'default'
            |   }
            |   targetConfigs {
            |       jvm {
            |           buildConfigField 'STRING', 'env', '$value'
            |       }
            |   }
            |}
            |
            |$kmpTargets
        """.trimMargin()

        projectDir.newFolder("app")
        File(projectDir.root, "app/build.gradle").writeText(subprojectBuildFile("app", "appV1"))
        projectDir.newFolder("lib")
        val libBuildFile = File(projectDir.root, "lib/build.gradle")
        libBuildFile.writeText(subprojectBuildFile("lib", "libV1"))

        val taskPaths = listOf(":app:generateBuildKonfig", ":lib:generateBuildKonfig")
        val arguments = taskPaths + "--stacktrace"

        val first = gradleRunner(projectDir).withArguments(arguments).build()
        taskPaths.forEach {
            assertThat(first.task(it)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        }

        val second = gradleRunner(projectDir).withArguments(arguments).build()
        taskPaths.forEach {
            assertThat(second.task(it)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        }

        // Change exactly one target value in :lib.
        libBuildFile.writeText(subprojectBuildFile("lib", "libV2"))

        val third = gradleRunner(projectDir).withArguments(arguments).build()
        assertThat(third.task(":lib:generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(third.task(":app:generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        val libDir = File(projectDir.root, "lib/$BUILDKONFIG_BUILD_DIR")
        assertThat(buildKonfigFile(libDir, "jvmMain", "com.sample.lib").readText())
            .contains("actual val env: String = \"libV2\"")
    }

    /**
     * The task wipes its output root before generating, so outputs of removed source
     * sets — and files planted by earlier, different configurations — must not linger.
     */
    @Test
    fun `stale generated outputs are cleaned when the config shape changes`() {
        buildFile.writeText(flavoredBuildFile())
        val buildDir = projectDir.buildKonfigDir()

        gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
            .assertBuildSuccessful()

        assertThat(buildKonfigFile(buildDir, "jvmMain", "com.sample").isFile).isTrue()
        assertThat(buildKonfigFile(buildDir, "iosX64Main", "com.sample").isFile).isTrue()

        // Plant a stale file that no current configuration would generate.
        val staleFile = File(buildDir, "commonMain/com/sample/Stale.kt")
        staleFile.writeText("package com.sample\n// stale\n")
        assertThat(staleFile.isFile).isTrue()

        // Drop targetConfigs entirely: the expect/actual split collapses to a single
        // concrete object and every per-target output directory must disappear.
        buildFile.writeText(
            """
            |$header
            |
            |buildkonfig {
            |   packageName = "com.sample"
            |
            |   defaultConfigs {
            |       buildConfigField 'STRING', 'env', 'default'
            |   }
            |}
            |
            |$kmpTargets
            """.trimMargin()
        )

        val rerun = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
        assertThat(rerun.task(":generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        assertThat(staleFile.exists()).isFalse()
        assertThat(File(buildDir, "jvmMain").exists()).isFalse()
        assertThat(File(buildDir, "iosX64Main").exists()).isFalse()
        assertThat(buildKonfigFile(buildDir, "commonMain", "com.sample").readText())
            .apply {
                contains("internal object BuildKonfig")
                doesNotContain("expect ")
            }
    }

    /**
     * Secrets only ever appear as fake fixtures. When the build fails, the failure
     * output must not echo configured field values — assert the fake secret stays out
     * of it, then prove the fixture is real by generating successfully with it.
     */
    @Test
    fun `failure output does not leak configured secret values`() {
        fun buildFileWithSecret(withPackageName: Boolean) = """
            |$header
            |
            |buildkonfig {
            |   ${if (withPackageName) "packageName = \"com.sample\"" else ""}
            |
            |   defaultConfigs {
            |       buildConfigField 'STRING', 'apiToken', '$FAKE_SECRET'
            |   }
            |}
            |
            |$kmpTargets
        """.trimMargin()

        // Blocked: packageName missing -> the build fails at task input validation.
        buildFile.writeText(buildFileWithSecret(withPackageName = false))

        val failure = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .buildAndFail()
        // The failure must point at the missing input, not echo configured values.
        assertThat(failure.output).contains("packageName")
        assertThat(failure.output).doesNotContain(FAKE_SECRET)

        // Recovery: provide the missing input and the same fixture generates fine.
        buildFile.writeText(buildFileWithSecret(withPackageName = true))
        val buildDir = projectDir.buildKonfigDir()

        gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
            .assertBuildSuccessful()

        // The secret really was configured — the negative assertion above is meaningful.
        assertThat(buildKonfigFile(buildDir, "commonMain", "com.sample").readText())
            .contains("public val apiToken: String = \"$FAKE_SECRET\"")
    }

    /**
     * Build-cache hit proven by the task outcome: after the outputs are deleted, an
     * unchanged input set must restore them FROM_CACHE instead of re-executing.
     */
    @Test
    fun `build cache hit is proven by task outcome after outputs are removed`() {
        appendGradleProperties("org.gradle.caching=true")
        buildFile.writeText(flavoredBuildFile())
        val buildDir = projectDir.buildKonfigDir()

        val first = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
        // The TestKit Gradle user home is shared across suite runs, so on a warm cache
        // this first run may already restore FROM_CACHE instead of executing.
        assertThat(first.task(":generateBuildKonfig")?.outcome)
            .isAnyOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)

        // Remove every output; inputs are untouched, so the next run must come from cache.
        File(projectDir.root, "build").deleteRecursively()
        assertThat(buildDir.exists()).isFalse()

        val second = gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
        assertThat(second.task(":generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
        assertThat(buildKonfigFile(buildDir, "jvmMain", "com.sample").readText())
            .contains("actual val env: String = \"jvm\"")
    }
}
