package com.redhat.podmortem.operator.health;

import com.redhat.podmortem.common.model.kube.patternlibrary.PatternLibrary;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Readiness check that ensures pattern libraries are synced before the operator is ready. */
@Readiness
@ApplicationScoped
public class PatternLibraryReadinessCheck implements HealthCheck {

    private static final Logger log = LoggerFactory.getLogger(PatternLibraryReadinessCheck.class);
    private static final String PATTERN_CACHE_DIR = "/shared/patterns";
    private static final int MAX_WAIT_MINUTES = 5;

    @Inject KubernetesClient client;

    private Instant startupTime = Instant.now();

    @Override
    public HealthCheckResponse call() {
        try {
            List<PatternLibrary> libraries =
                    client.resources(PatternLibrary.class).inAnyNamespace().list().getItems();

            if (libraries.isEmpty()) {
                log.debug("No PatternLibrary resources found, marking as ready");
                return HealthCheckResponse.named("pattern-library-sync").up().build();
            }

            if (startupTime.plus(MAX_WAIT_MINUTES, ChronoUnit.MINUTES).isBefore(Instant.now())) {
                log.warn("Pattern library sync grace period exceeded, reporting ready anyway");
                return HealthCheckResponse.named("pattern-library-sync").up().build();
            }

            Path cacheDir = Paths.get(PATTERN_CACHE_DIR);
            if (!Files.exists(cacheDir)) {
                log.debug("Pattern cache directory does not exist: {}", PATTERN_CACHE_DIR);
                return HealthCheckResponse.named("pattern-library-sync").down().build();
            }

            boolean hasPatterns = false;
            try (var stream = Files.list(cacheDir)) {
                hasPatterns =
                        stream.filter(Files::isRegularFile)
                                .anyMatch(
                                        path ->
                                                path.toString().endsWith(".yml")
                                                        || path.toString().endsWith(".yaml"));
            }

            if (!hasPatterns) {
                log.debug("No pattern files found in cache directory");
                return HealthCheckResponse.named("pattern-library-sync").down().build();
            }

            boolean hasSuccessfulSync =
                    libraries.stream()
                            .anyMatch(
                                    lib ->
                                            lib.getStatus() != null
                                                    && "Ready"
                                                            .equalsIgnoreCase(
                                                                    lib.getStatus().getPhase()));

            if (!hasSuccessfulSync) {
                log.debug("No PatternLibrary has successful sync status");
                return HealthCheckResponse.named("pattern-library-sync").down().build();
            }

            log.trace("Pattern library readiness check passed");
            return HealthCheckResponse.named("pattern-library-sync").up().build();

        } catch (Exception e) {
            log.error("Error during pattern library readiness check", e);
            return HealthCheckResponse.named("pattern-library-sync").down().build();
        }
    }
}
