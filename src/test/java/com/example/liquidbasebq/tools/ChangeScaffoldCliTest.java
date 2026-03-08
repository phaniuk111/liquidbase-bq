package com.example.liquidbasebq.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChangeScaffoldCliTest {

    @Test
    @DisplayName("parseArgs correctly parses arguments into a map")
    void testParseArgs() throws Exception {
        Method parseArgsMethod = ChangeScaffoldCli.class.getDeclaredMethod("parseArgs", String[].class);
        parseArgsMethod.setAccessible(true);

        String[] args = {
                "--name", "test-migration",
                "--domain", "users",
                "--contexts", "dev,uat",
                "--labels", "jira-123",
                "--author", "test@example.com",
                "--register-master"
        };

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) parseArgsMethod.invoke(null, (Object) args);

        assertEquals("test-migration", result.get("--name"));
        assertEquals("users", result.get("--domain"));
        assertEquals("dev,uat", result.get("--contexts"));
        assertEquals("jira-123", result.get("--labels"));
        assertEquals("test@example.com", result.get("--author"));
        assertEquals("true", result.get("--register-master"));
    }

    @Test
    @DisplayName("validateArgs throws exception when required args are missing")
    void testValidateArgsMissing() throws Exception {
        Method validateArgsMethod = ChangeScaffoldCli.class.getDeclaredMethod("validateArgs", Map.class);
        validateArgsMethod.setAccessible(true);

        Map<String, String> invalidArgs = Map.of(
                "--name", "test"
        // Missing domain, contexts, labels
        );

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> {
            validateArgsMethod.invoke(null, invalidArgs);
        });

        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("Missing required argument: --domain"));
    }
}
