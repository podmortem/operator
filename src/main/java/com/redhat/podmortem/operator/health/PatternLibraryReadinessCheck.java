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

            log.debug("Found {} PatternLibrary resources", libraries.size());

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
            long patternCount = 0;
            try (var stream = Files.walk(cacheDir)) {
                patternCount =
                        stream.filter(Files::isRegularFile)
                                .filter(
                                        path ->
                                                path.toString().endsWith(".yml")
                                                        || path.toString().endsWith(".yaml"))
                                .count();
                hasPatterns = patternCount > 0;
            }

            if (!hasPatterns) {
                log.debug("No pattern files found in cache directory");
                return HealthCheckResponse.named("pattern-library-sync").down().build();
            }

            log.debug("Found {} pattern files in cache directory", patternCount);

            boolean hasSuccessfulSync =
                    libraries.stream()
                            .anyMatch(
                                    lib -> {
                                        if (lib.getStatus() == null) {
                                            log.trace(
                                                    "PatternLibrary {} has null status",
                                                    lib.getMetadata().getName());
                                            return false;
                                        }
                                        String phase = lib.getStatus().getPhase();
                                        log.trace(
                                                "PatternLibrary {} has phase: {}",
                                                lib.getMetadata().getName(),
                                                phase);
                                        // Accept either "Ready" or "Success" as valid states
                                        return "Ready".equalsIgnoreCase(phase)
                                                || "Success".equalsIgnoreCase(phase);
                                    });

            if (!hasSuccessfulSync) {
                log.debug("No PatternLibrary has successful sync status");
                libraries.forEach(
                        lib -> {
                            if (lib.getStatus() != null) {
                                log.debug(
                                        "PatternLibrary {} status: phase={}",
                                        lib.getMetadata().getName(),
                                        lib.getStatus().getPhase());
                            } else {
                                log.debug(
                                        "PatternLibrary {} has null status",
                                        lib.getMetadata().getName());
                            }
                        });
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
