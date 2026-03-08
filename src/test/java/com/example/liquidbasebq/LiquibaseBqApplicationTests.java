package com.example.liquidbasebq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.CommandLineRunner;
import javax.sql.DataSource;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "BQ_PROJECT_ID=mock",
        "BQ_DATASET_ID=mock"
})
@ActiveProfiles("dev1")
class LiquibaseBqApplicationTests {

    @MockBean
    private DataSource dataSource;

    @MockBean
    private CommandLineRunner schemaManagerRunner;

    @Test
    void contextLoads() {
        // This test simply verifies that the Spring application context starts up
        // successfully.
    }
}
