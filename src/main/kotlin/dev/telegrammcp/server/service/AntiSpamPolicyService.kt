package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.AntiSpamProperties.RuleCeilings
import dev.telegrammcp.server.config.AntiSpamProperties.RuleProps
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
 * Hard ceilings from [AntiSpamProperties.ceilings] are then applied on top —
 * a runtime or yaml override cannot raise a limit above its ceiling, nor
 * shorten a window below its floor. This keeps the orchestrator from ever
 * exceeding operator-defined safety bounds even if anti-spam state is
 * tampered with.
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
        runCatching {
            val raw = Files.readString(path)
            if (raw.isBlank()) return@runCatching
            val parsed: Map<String, RuleProps> = mapper.readValue(raw)
            runtimeOverrides.clear()
            runtimeOverrides.putAll(parsed)
            log.info("AntiSpamPolicyService: loaded {} runtime override(s) from {}", parsed.size, path)
        }.onFailure {
            log.warn("AntiSpamPolicyService: failed to load overrides from {} — {}", path, it.message)
        }
    }

    /**
     * Returns the layered + clamped effective rule for [toolName].
     * [base] is typically the built-in rule (or [AntiSpamProperties.defaultRule]).
     */
    fun effectiveRule(toolName: String, base: RuleProps): RuleProps {
        var rule = base
        props.rules[toolName]?.let { rule = it }
        runtimeOverrides[toolName]?.let { rule = it }
        val ceil = props.ceilings[toolName] ?: return rule
        return clamp(rule, ceil)
    }

    /**
     * Snapshot of all currently-active runtime overrides (already clamped to
     * ceilings). Useful for admin diagnostics.
     */
    fun listOverrides(): Map<String, RuleProps> =
        runtimeOverrides.mapValues { (tool, rule) ->
            val ceil = props.ceilings[tool]
            if (ceil == null) rule else clamp(rule, ceil)
        }

    /**
     * Installs (or replaces) a runtime override for [toolName]. The override
     * is first clamped to the per-tool ceiling, then persisted to disk if
     * [AntiSpamProperties.overridesFile] is configured.
     *
     * Returns the clamped, effective rule.
     */
    fun setOverride(toolName: String, override: RuleProps): RuleProps {
        val ceil = props.ceilings[toolName]
        val clamped = if (ceil == null) override else clamp(override, ceil)
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
}
