package com.example.transfers.acceptance;

import com.example.transfers.support.IntegrationTest;
import io.karatelabs.junit6.Karate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Runs every feature in src/test/resources/acceptance against the real application (random port,
 * Postgres and Kafka in Testcontainers). The ledgers are played by {@link TestHooks}.
 */
@IntegrationTest
class AcceptanceTest {

    @Autowired
    ApplicationContext context;

    @Karate.Test
    Karate run_AcceptanceFeatures_AllScenariosPass() {
        TestHooks.init(context);
        var port = context.getEnvironment().getRequiredProperty("local.server.port");
        // Filesystem path rather than classpath: Karate 2.1 cannot resolve classpath: features on Windows.
        return Karate.run("src/test/resources/acceptance")
                .systemProperty("baseUrl", "http://localhost:" + port)
                .outputDir("build/karate-reports");
    }
}
