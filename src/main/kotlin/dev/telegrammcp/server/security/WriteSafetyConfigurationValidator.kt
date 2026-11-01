package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.config.ServerModeProperties.ApprovalMode
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Refuses to start when writes are on and the only destructive-action gate is
 * `"confirmed": true` inside the tool call. The model can set that argument,
 * including because a Telegram message told it to.
 */
@Component
class WriteSafetyConfigurationValidator(
    private val serverMode: ServerModeProperties,
) {

    @PostConstruct
    fun validate() {
        val modelConfirmsWrites = !serverMode.readOnly &&
            serverMode.confirmation.approval == ApprovalMode.OFF &&
            !serverMode.confirmation.allowModelConfirmedWrites
        require(!modelConfirmsWrites) {
            "MCP_READ_ONLY=false with MCP_DESTRUCTIVE_APPROVAL=off lets the model approve destructive tools " +
                "by sending confirmed:true. Set MCP_DESTRUCTIVE_APPROVAL to auto, elicitation, or loopback. " +
                "Set MCP_ALLOW_MODEL_CONFIRMED_WRITES=true only as an intentional exception."
        }
    }
}
