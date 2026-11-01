package dev.telegrammcp.server.security

import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.config.ServerModeProperties.ApprovalMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WriteSafetyConfigurationValidatorTest {

    @Test
    fun `writes without human approval refuse to start`() {
        val validator = validator(readOnly = false, approval = ApprovalMode.OFF, allowModel = false)
        assertThrows<IllegalArgumentException> { validator.validate() }
    }

    @Test
    fun `read only mode may leave approval off`() {
        validator(readOnly = true, approval = ApprovalMode.OFF, allowModel = false).validate()
    }

    @Test
    fun `human approval allows writes`() {
        validator(readOnly = false, approval = ApprovalMode.AUTO, allowModel = false).validate()
    }

    @Test
    fun `explicit exception allows model confirmed writes`() {
        validator(readOnly = false, approval = ApprovalMode.OFF, allowModel = true).validate()
    }

    private fun validator(
        readOnly: Boolean,
        approval: ApprovalMode,
        allowModel: Boolean,
    ) = WriteSafetyConfigurationValidator(
        ServerModeProperties(
            readOnly = readOnly,
            confirmation = ServerModeProperties.ConfirmationProps(
                approval = approval,
                allowModelConfirmedWrites = allowModel,
            ),
        ),
    )
}
