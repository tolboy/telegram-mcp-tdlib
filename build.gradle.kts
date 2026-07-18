import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Path
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.telegrammcp"

// Version sourcing (priority, first hit wins):
//   1. Explicit override from CI / caller — `-PreleaseVersion=X.Y.Z` or env
//      `RELEASE_VERSION=X.Y.Z`. The docker-build.yml workflow sets this from
//      the git tag that triggered the release (vX.Y.Z → X.Y.Z).
//   2. `git describe --tags --always --dirty` — dynamic derivation for local
//      `gradlew bootJar` runs (e.g. scripts/hotfix-container-jar.bat). Yields
//      "1.0.0" on a clean checkout of a tag, "1.0.0-3-gabcdef1-dirty" when
//      ahead / modified. This way buildProperties.version and TDLib's
//      applicationVersion (reported to Telegram → Devices) stay in sync with
//      whatever commit actually produced the jar, instead of a stale literal.
//   3. "0.0.0-unknown" fallback — only reached when the directory is not a
//      git checkout (e.g. a tarball). Surface the oddness instead of hiding
//      it behind a made-up number.
val releaseVersionOverride: String? = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .orNull

fun gitDescribe(): String? = runCatching {
    val proc = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
}.getOrNull()?.removePrefix("v")

version = releaseVersionOverride?.removePrefix("v") ?: gitDescribe() ?: "0.0.0-unknown"
description = "Production-ready MCP server for Telegram integration"

// Allow builds outside synchronized folders without hard-coding a platform path.
val buildDirOverride = providers.gradleProperty("ktm.buildDir")
    .orElse(providers.environmentVariable("KTM_BUILD_DIR"))
    .orNull

when {
    !buildDirOverride.isNullOrBlank() -> layout.buildDirectory.set(file(buildDirOverride))
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> {
        val tempBuildDir = Path.of(
            System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"),
            "ktm-build",
            rootProject.name,
        ).toString()
        layout.buildDirectory.set(file(tempBuildDir))
    }
}

// ─── Java 25 Toolchain ──────────────────────────────────────────────────────
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
    maven("https://mvn.mchv.eu/repository/mchv/") // tdlight-java (TDLib)
}

// ─── Dependency versions not managed by Spring Boot BOM ─────────────────────
val mcpSdkVersion = "2.0.0"
val springAiMcpVersion = "2.0.0"
val tdlightVersion = "3.5.3+td.1.8.65"
val tdlightNativesVersion = "4.0.589"
val resilience4jVersion = "2.4.0"
val caffeineVersion = "3.2.4"
val logstashVersion = "9.0"
val mockkVersion = "1.14.11"

dependencies {
    constraints {
        implementation("io.modelcontextprotocol.sdk:mcp:$mcpSdkVersion") {
            because("Keep MCP SDK modules aligned with Spring AI 2.0.0")
        }
        implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:$mcpSdkVersion") {
            because("Keep MCP SDK modules on the same patched line")
        }
        implementation("io.modelcontextprotocol.sdk:mcp-core:$mcpSdkVersion") {
            because("Keep MCP SDK modules on the supported 2.0.0 line")
        }
    }

    // ── Spring Boot starters ────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // ── MCP ─────────────────────────────────────────────────────────────────
    implementation("org.springframework.ai:spring-ai-starter-mcp-server:$springAiMcpVersion")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc:$springAiMcpVersion")

    // Local QR rendering for the explicit loopback-only authentication wizard.
    implementation("com.google.zxing:core:3.5.4")

    // ── Telegram (TDLib via tdlight-java) ─────────────────────────────────────
    implementation("it.tdlight:tdlight-java:$tdlightVersion")
    // Classifier-based natives. Package every supported desktop/server target
    // in the Boot JAR; tdlight selects the matching classifier at runtime.
    implementation("it.tdlight:tdlight-natives:$tdlightNativesVersion:linux_amd64_gnu_ssl3")
    implementation("it.tdlight:tdlight-natives:$tdlightNativesVersion:linux_arm64_gnu_ssl3")
    implementation("it.tdlight:tdlight-natives:$tdlightNativesVersion:windows_amd64")
    implementation("it.tdlight:tdlight-natives:$tdlightNativesVersion:macos_arm64")

    // ── Caching (entity resolver) ──────────────────────────────────────────
    implementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")

    // ── Kotlin ──────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // ── Observability ───────────────────────────────────────────────────────
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ── Resilience ──────────────────────────────────────────────────────────
    implementation("io.github.resilience4j:resilience4j-spring-boot4:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-micrometer:$resilience4jVersion")

    // ── Structured logging ──────────────────────────────────────────────────
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashVersion")

    // ── Test ────────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

// ─── Kotlin compiler options ────────────────────────────────────────────────
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}

tasks.bootJar {
    archiveFileName.set("telegram-mcp-server.jar")
}

// Keep the MCP server version, Spring Boot build-info, and release artifact in
// sync. The @...@ token avoids Gradle expanding Spring's ${...} placeholders.
tasks.processResources {
    val applicationVersion = project.version.toString()
    inputs.property("applicationVersion", applicationVersion)
    filesMatching("application.yml") {
        filter { line: String -> line.replace("@application.version@", applicationVersion) }
    }
}

/** Verifies that the assembled Boot JAR contains every supported TDLight native package. */
tasks.register("verifyNativeRuntimeCoverage") {
    group = "verification"
    description = "Verifies TDLight native runtime packages in the Boot JAR"
    dependsOn(tasks.bootJar)
    doLast {
        val bootJar = tasks.bootJar.get().archiveFile.get().asFile
        val requiredClassifiers = listOf(
            "linux_amd64_gnu_ssl3",
            "linux_arm64_gnu_ssl3",
            "windows_amd64",
            "macos_arm64",
        )
        val entries = ZipFile(bootJar).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        val missing = requiredClassifiers.filter { classifier ->
            entries.none { entry -> entry.contains("tdlight-natives") && entry.contains(classifier) }
        }
        check(missing.isEmpty()) {
            "Boot JAR ${bootJar.name} is missing TDLight native package(s): ${missing.joinToString()}"
        }
        logger.lifecycle("Verified TDLight native runtime coverage: ${requiredClassifiers.joinToString()}")
    }
}

/** Ensures a release JAR advertises the same version Gradle used to build it. */
tasks.register("verifyReleaseMetadata") {
    group = "verification"
    description = "Verifies embedded MCP and Spring Boot release metadata"
    dependsOn(tasks.bootJar)
    doLast {
        val bootJar = tasks.bootJar.get().archiveFile.get().asFile
        val expectedVersion = project.version.toString()
        ZipFile(bootJar).use { zip ->
            val applicationYml = zip.getInputStream(zip.getEntry("BOOT-INF/classes/application.yml"))
                .bufferedReader()
                .readText()
            val buildInfo = zip.getInputStream(zip.getEntry("META-INF/build-info.properties"))
                .bufferedReader()
                .readText()
            check("version: \${MCP_SERVER_VERSION:$expectedVersion}" in applicationYml) {
                "MCP server version in application.yml does not match $expectedVersion"
            }
            check("build.version=$expectedVersion" in buildInfo) {
                "Spring Boot build-info version does not match $expectedVersion"
            }
            check("@application.version@" !in applicationYml) {
                "application.yml contains an unexpanded build-version token"
            }
        }
        logger.lifecycle("Verified release metadata version: $expectedVersion")
    }
}

springBoot {
    buildInfo()
}
