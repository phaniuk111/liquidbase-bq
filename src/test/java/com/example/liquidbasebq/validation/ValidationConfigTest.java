package com.example.liquidbasebq.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ValidationConfigTest {

    private ValidationConfig validationConfig;

    @BeforeEach
    void setUp() {
        validationConfig = new ValidationConfig();
    }

    @Test
    @DisplayName("Should detect all governance violations in strict validation")
    void testStrictValidationViolations() {
        ReflectionTestUtils.setField(validationConfig, "changeLogFile",
                "classpath:db/changelog/validation/test-changelog.xml");

        assertThatThrownBy(() -> validationConfig.validateStrict())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRICT VALIDATION FAILED")
                .hasMessageContaining("Missing <rollback> block")
                .hasMessageContaining("Missing 'context' attribute")
                .hasMessageContaining("Missing 'labels' attribute")
                .hasMessageContaining("Destructive operation detected without <preConditions>")
                .hasMessageContaining("Unreadable SQL file detected")
                .hasMessageContaining("destructive-sqlfile");
    }

    @Test
    @DisplayName("Should pass strict validation for compliant changelog")
    void testStrictValidationCompliant() {
        ReflectionTestUtils.setField(validationConfig, "changeLogFile",
                "classpath:db/changelog/validation/valid-changelog.xml");

        assertThatCode(() -> validationConfig.validateStrict())
                .doesNotThrowAnyException();
    }
}
