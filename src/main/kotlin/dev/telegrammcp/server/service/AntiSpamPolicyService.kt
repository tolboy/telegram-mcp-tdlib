package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.AntiSpamProperties.RuleCeilings
import dev.telegrammcp.server.config.AntiSpamProperties.RuleProps
import dev.telegrammcp.server.security.LocalAccessChecks
import dev.telegrammcp.server.util.StructuredLogger
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the **effective** anti-spam rule for each tool, layering:
 *
 *  1. Built-in rule from [AntiSpamGuardService] (passed in as `base`).
 *  2. Yaml-configured override from [AntiSpamProperties.rules].
 *  3. Runtime override persisted in the local JSON file
 *     ([AntiSpamProperties.overridesFile]), set via admin tooling.
 *
 * Hard ceilings from [AntiSpamProperties.ceilings] are then applied on top.
 * A tool without an explicit ceiling still clamps a runtime override to
 * [DEFAULT_RUNTIME_CEILING], so a planted overrides file cannot disable the
 * guard or raise a limit without bound. Operator yaml is left alone unless
 * that tool has an explicit ceiling.
 *
 * The JSON file is written atomically (temp + ATOMIC_MOVE) to avoid partial
 * reads at startup. `null` / blank `overridesFile` disables persistence and
 * runtime overrides become in-memory only.
 */
@Service
class AntiSpamPolicyService(
    private val props: AntiSpamProperties,
    private val platformPaths: PlatformPaths = PlatformPaths(),
) {

    private val log = StructuredLogger.forClass<AntiSpamPolicyService>()
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val runtimeOverrides = ConcurrentHashMap<String, RuleProps>()

    private val overridesPath: Path? = props.overridesFile
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { platformPaths.resolveApplicationPath(it, "anti-spam.overrides-file") }

    @PostConstruct
    fun loadOverrides() {
        val path = overridesPath ?: return
        if (!Files.exists(path)) {
            log.info("AntiSpamPolicyService: no overrides file at {}, starting clean", path)
            return
        }
        LocalAccessChecks.rejectSharedWrite(path, "anti-spam.overrides-file")
        val raw = try {
            Files.readString(path)
        } catch (error: Exception) {
            throw IllegalStateException("anti-spam overrides file $path cannot be read", error)
        }
        if (raw.isBlank()) return
        val parsed = try {
            mapper.readValue<Map<String, RuleProps>>(raw)
        } catch (error: Exception) {
            throw IllegalStateException(
                "anti-spam overrides file $path is not valid JSON. Fix or remove it before startup.",
                error,
            )
        }
        runtimeOverrides.clear()
        runtimeOverrides.putAll(parsed)
        log.info("AntiSpamPolicyService: loaded {} runtime override(s) from {}", parsed.size, path)
    }

    /**
     * Returns the layered + clamped effective rule for [toolName].
     * [base] is typically the built-in rule (or [AntiSpamProperties.defaultRule]).
     */
    fun effectiveRule(toolName: String, base: RuleProps): RuleProps {
        var rule = base
        props.rules[toolName]?.let { rule = it }
        val explicit = props.ceilings[toolName]
        runtimeOverrides[toolName]?.let { runtime ->
            rule = clamp(runtime.copy(exempt = false), explicit ?: DEFAULT_RUNTIME_CEILING)
        }
        return if (explicit != null) clamp(rule, explicit) else rule
    }

    /**
     * Snapshot of all currently-active runtime overrides (already clamped to
     * ceilings). Useful for admin diagnostics.
     */
    fun listOverrides(): Map<String, RuleProps> =
        runtimeOverrides.mapValues { (tool, rule) ->
            clamp(rule.copy(exempt = false), props.ceilings[tool] ?: DEFAULT_RUNTIME_CEILING)
        }

    /**
     * Installs (or replaces) a runtime override for [toolName]. The override
     * is first clamped to the per-tool ceiling, then persisted to disk if
     * [AntiSpamProperties.overridesFile] is configured.
     *
     * Returns the clamped, effective rule.
     */
    fun setOverride(toolName: String, override: RuleProps): RuleProps {
        val ceil = props.ceilings[toolName] ?: DEFAULT_RUNTIME_CEILING
        val clamped = clamp(override.copy(exempt = false), ceil)
        runtimeOverrides[toolName] = clamped
        persist()
        log.info("AntiSpamPolicyService: override set tool='{}' rule={}", toolName, clamped)
        return clamped
    }

    /** Removes any runtime override for [toolName]. Returns true if one existed. */
    fun resetOverride(toolName: String): Boolean {
        val removed = runtimeOverrides.remove(toolName) != null
        if (removed) {
            persist()
            log.info("AntiSpamPolicyService: override reset tool='{}'", toolName)
        }
        return removed
    }

    private fun clamp(rule: RuleProps, ceil: RuleCeilings): RuleProps = rule.copy(
        maxOps = ceil.maxOpsCeiling?.let { rule.maxOps.coerceAtMost(it) } ?: rule.maxOps,
        maxOpsExternal = rule.maxOpsExternal?.let { v ->
            ceil.maxOpsCeiling?.let { v.coerceAtMost(it) } ?: v
        },
        windowMs = ceil.windowMsFloor?.let { rule.windowMs.coerceAtLeast(it) } ?: rule.windowMs,
        windowMsExternal = rule.windowMsExternal?.let { v ->
            ceil.windowMsFloor?.let { v.coerceAtLeast(it) } ?: v
        },
        dailyMax = when {
            ceil.dailyMaxCeiling == null -> rule.dailyMax
            rule.dailyMax <= 0 -> ceil.dailyMaxCeiling
            else -> rule.dailyMax.coerceAtMost(ceil.dailyMaxCeiling)
        },
        dedupWindowMs = ceil.dedupWindowMsFloor?.let {
            rule.dedupWindowMs.coerceAtLeast(it)
        } ?: rule.dedupWindowMs,
    )

    private fun persist() {
        val path = overridesPath ?: return
        runCatching {
            val parent = path.parent
            if (parent != null) Files.createDirectories(parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            val json = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(runtimeOverrides.toMap())
            Files.writeString(tmp, json)
            Files.move(
                tmp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            log.warn("AntiSpamPolicyService: failed to persist overrides to {} — {}", path, it.message)
        }
    }

    companion object {
        /**
         * Applies only to runtime overrides. Operator yaml can still set a
         * looser rule; an explicit per-tool ceiling then clamps both layers.
         */
        val DEFAULT_RUNTIME_CEILING = RuleCeilings(
            maxOpsCeiling = 60,
            dailyMaxCeiling = 500,
            windowMsFloor = 1_000,
            dedupWindowMsFloor = 1_000,
        )
    }
}
