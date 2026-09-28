package com.codingfeline.buildkonfig.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

/**
 * TestKit matrix over the error-prone seams of the plugin: config merge priority
 * (flavored target > target > flavored default > default), Kotlin literal escaping
 * (quotes, newlines, `$`, non-BMP characters), nullable fields, and multiple Native
 * targets — each verified against the generated sources, plus a configuration-cache
 * pass over the combined matrix.
 *
 * Every fixture carries [gradleUserHomeIsolationGuard], so a regression that makes
 * test projects read the user Gradle configuration fails the build immediately.
 */
class BuildKonfigPluginMatrixTest : BaseGradlePluginTest() {

    private val header = buildFileHeader("kotlin-multiplatform") + "\n" + gradleUserHomeIsolationGuard

    private val kmpTargets = """
        |kotlin {
        |  jvm()
        |  iosX64()
        |  iosArm64()
        |  linuxX64()
        |}
    """.trimMargin()

    private val mergePriorityConfig = """
        |buildkonfig {
        |   packageName = "com.sample"
        |
        |   defaultConfigs {
        |       buildConfigField 'STRING', 'priority', 'default'
        |       buildConfigField 'STRING', 'defaultOnly', 'd'
        |   }
        |   defaultConfigs("dev") {
        |       buildConfigField 'STRING', 'priority', 'flavoredDefault'
        |   }
        |   targetConfigs {
        |       jvm {
        |           buildConfigField 'STRING', 'priority', 'target'
        |           buildConfigField 'STRING', 'jvmOnly', 'j'
        |       }
        |   }
        |   targetConfigs("dev") {
        |       jvm {
        |           buildConfigField 'STRING', 'priority', 'flavoredTarget'
        |       }
        |   }
        |}
    """.trimMargin()

    /**
     * Two nearly identical invocations — the only difference is the `buildkonfig.flavor`
     * property — must take different merge paths and produce different values. Each
     * flavor level asserts its expected winner in the generated source.
     */
    @Test
    fun `merge priority - flavored target wins over target, flavored default and default`() {
        buildFile.writeText(
            """
            |$header
            |
            |$mergePriorityConfig
            |
            |$kmpTargets
            """.trimMargin()
        )

        val buildDir = projectDir.buildKonfigDir()

        // Path 1: flavor=dev — flavored entries win at every level they exist.
        gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "-Pbuildkonfig.flavor=dev", "--stacktrace")
            .build()
            .assertBuildSuccessful()

        assertThat(buildKonfigFile(buildDir, "commonMain", "com.sample").readText())
            .isEqualTo(
                """
                |package com.sample
                |
                |import kotlin.String
                |
                |internal expect object BuildKonfig {
                |  public val priority: String
                |
                |  public val defaultOnly: String
                |}
                |
            """.trimMargin()
            )

        // Flavored targetConfig beats targetConfig, flavored defaultConfig and defaultConfig.
        assertThat(buildKonfigFile(buildDir, "jvmMain", "com.sample").readText())
            .apply {
                contains("public actual val priority: String = \"flavoredTarget\"")
                contains("public actual val defaultOnly: String = \"d\"")
                // Target-specific field: present here, absent from commonMain, not `actual`.
                contains("public val jvmOnly: String = \"j\"")
                doesNotContain("actual val jvmOnly")
            }

        // Native targets without their own targetConfig inherit the flavored default.
        listOf("iosX64Main", "iosArm64Main", "linuxX64Main").forEach { sourceSet ->
            assertThat(buildKonfigFile(buildDir, sourceSet, "com.sample").readText())
                .apply {
                    contains("internal actual object BuildKonfig")
                    contains("public actual val priority: String = \"flavoredDefault\"")
                    contains("public actual val defaultOnly: String = \"d\"")
                    doesNotContain("jvmOnly")
                }
        }

        // Path 2: no flavor — the unflavored merge path wins instead.
        gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
            .assertBuildSuccessful()

        assertThat(buildKonfigFile(buildDir, "jvmMain", "com.sample").readText())
            .contains("public actual val priority: String = \"target\"")
        listOf("iosX64Main", "iosArm64Main", "linuxX64Main").forEach { sourceSet ->
            assertThat(buildKonfigFile(buildDir, sourceSet, "com.sample").readText())
                .contains("public actual val priority: String = \"default\"")
        }
    }

    /**
     * Kotlin literal escaping is the second error-prone seam: values pass through
     * Groovy DSL -> FieldSpec -> KotlinPoet `%S`. Quotes, newlines, `$` and non-BMP
     * characters must survive the round trip as valid, equivalent Kotlin literals.
     */
    @Test
    fun `string escaping - quotes, newline, dollar sign and non-BMP characters`() {
        buildFile.writeText(
            """
            |$header
            |
            |buildkonfig {
            |   packageName = "com.sample"
            |
            |   defaultConfigs {
            |       buildConfigField 'STRING', 'quoted', 'say "hi"'
            |       buildConfigField 'STRING', 'multiline', 'line1\nline2'
            |       buildConfigField 'STRING', 'dollar', 'price ${'$'}100'
            |       buildConfigField 'STRING', 'emoji', 'icon 😀'
            |   }
            |}
            |
            |$kmpTargets
            """.trimMargin()
        )

        val buildDir = projectDir.buildKonfigDir()

        gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
            .assertBuildSuccessful()

        // No targetConfigs -> a single concrete object in commonMain.
        val content = buildKonfigFile(buildDir, "commonMain", "com.sample").readText()
        assertThat(content).apply {
            contains("internal object BuildKonfig")
            doesNotContain("expect ")
            // Quotes are escaped inside a regular string literal.
            contains("public val quoted: String = \"say \\\"hi\\\"\"")
            // Newlines become a raw string with a margin prefix; KotlinPoet re-indents
            // the margin lines to the property's continuation level.
            contains(
                """
                |public val multiline: String = ""${'"'}
                |      |line1
                |      |line2
                |      ""${'"'}.trimMargin()
                """.trimMargin()
            )
            // `$` is escaped so it cannot start a template in the generated source.
            contains("public val dollar: String = \"price \${'$'}100\"")
            // Non-BMP characters are emitted as-is (UTF-8).
            contains("public val emoji: String = \"icon 😀\"")
        }

        // Without target configs, no per-target files are generated at all.
        listOf("jvmMain", "iosX64Main", "iosArm64Main", "linuxX64Main").forEach { sourceSet ->
            assertThat(buildKonfigFile(buildDir, sourceSet, "com.sample").exists()).isFalse()
        }
    }

    @Test
    fun `nullable fields merge across default and target configs`() {
        buildFile.writeText(
            """
            |$header
            |
            |buildkonfig {
            |   packageName = "com.sample"
            |
            |   defaultConfigs {
            |       buildConfigField 'STRING', 'nullableDefault', null, nullable: true
            |       buildConfigField 'INT', 'nullableInt', '10', nullable: true
            |   }
            |   targetConfigs {
            |       jvm {
            |           buildConfigField 'STRING', 'nullableDefault', 'jvmValue'
            |       }
            |   }
            |}
            |
            |$kmpTargets
            """.trimMargin()
        )

        val buildDir = projectDir.buildKonfigDir()

        gradleRunner(projectDir)
            .withArguments("generateBuildKonfig", "--stacktrace")
            .build()
            .assertBuildSuccessful()

        assertThat(buildKonfigFile(buildDir, "commonMain", "com.sample").readText())
            .apply {
                contains("public val nullableDefault: String?")
                contains("public val nullableInt: Int?")
            }

        // The jvm targetConfig overrides the nullable default with a non-null value.
        assertThat(buildKonfigFile(buildDir, "jvmMain", "com.sample").readText())
            .apply {
                contains("public actual val nullableDefault: String = \"jvmValue\"")
                contains("public actual val nullableInt: Int? = 10")
            }

        // Native targets inherit the nullable default, including the `= null` initializer.
        assertThat(buildKonfigFile(buildDir, "iosX64Main", "com.sample").readText())
            .apply {
                contains("public actual val nullableDefault: String? = null")
                contains("public actual val nullableInt: Int? = 10")
            }
    }

    /**
     * The full matrix in one fixture, stored to and reused from the configuration cache.
     * The second run asserts both the cache reuse and — behaviorally — that the generated
     * sources are still exactly the expected ones.
     */
    @Test
    fun `full matrix is compatible with the configuration cache`() {
        buildFile.writeText(
            """
            |$header
            |
            |buildkonfig {
            |   packageName = "com.sample"
            |
            |   defaultConfigs {
            |       buildConfigField 'STRING', 'priority', 'default'
            |       buildConfigField 'STRING', 'escaped', 'say "hi" ${'$'}100 😀'
            |       buildConfigField 'STRING', 'nullableDefault', null, nullable: true
            |   }
            |   defaultConfigs("dev") {
            |       buildConfigField 'STRING', 'priority', 'flavoredDefault'
            |   }
            |   targetConfigs {
            |       jvm {
            |           buildConfigField 'STRING', 'priority', 'target'
            |       }
            |   }
            |   targetConfigs("dev") {
            |       jvm {
            |           buildConfigField 'STRING', 'priority', 'flavoredTarget'
            |       }
            |   }
            |}
            |
            |$kmpTargets
            """.trimMargin()
        )

        val buildDir = projectDir.buildKonfigDir()
        val arguments = listOf(
            "generateBuildKonfig",
            "-Pbuildkonfig.flavor=dev",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "--stacktrace",
        )

        val firstRun = gradleRunner(projectDir).withArguments(arguments).build()
        assertThat(firstRun.output).contains("Configuration cache entry stored")
        assertThat(firstRun.task(":generateBuildKonfig")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val secondRun = gradleRunner(projectDir).withArguments(arguments).build()
        assertThat(secondRun.output).contains("Configuration cache entry reused")

        // Behavioral proof beyond the log line: the merged, escaped, nullable matrix
        // output is exactly what the flavor=dev path must produce.
        assertThat(buildKonfigFile(buildDir, "jvmMain", "com.sample").readText())
            .apply {
                contains("public actual val priority: String = \"flavoredTarget\"")
                contains("public actual val escaped: String = \"say \\\"hi\\\" \${'$'}100 😀\"")
                contains("public actual val nullableDefault: String? = null")
            }
        assertThat(buildKonfigFile(buildDir, "iosX64Main", "com.sample").readText())
            .contains("public actual val priority: String = \"flavoredDefault\"")
    }
}
