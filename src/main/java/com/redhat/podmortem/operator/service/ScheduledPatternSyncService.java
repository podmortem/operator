package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.kube.patternlibrary.PatternLibrary;
import com.redhat.podmortem.common.model.kube.patternlibrary.PatternLibraryStatus;
import com.redhat.podmortem.common.model.kube.patternlibrary.PatternRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ScheduledPatternSyncService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPatternSyncService.class);

    @Inject KubernetesClient client;

    @Inject PatternSyncService patternSyncService;

    /**
     * Run periodically to check if any pattern libraries need to be synced Interval is configurable
     * via pattern.sync.check.interval property
     */
    @Scheduled(every = "${pattern.sync.check.interval}")
    public void checkAndSyncPatternLibraries() {
        log.debug("Checking pattern libraries for scheduled sync");

        try {
            // Get all PatternLibrary resources from all namespaces
            List<PatternLibrary> patternLibraries =
                    client.resources(PatternLibrary.class).inAnyNamespace().list().getItems();

            for (PatternLibrary library : patternLibraries) {
                try {
                    if (needsSync(library)) {
                        log.info(
                                "Scheduled sync needed for pattern library: {}",
                                library.getMetadata().getName());
                        syncPatternLibrary(library);
                    }
                } catch (Exception e) {
                    log.error(
                            "Error checking sync status for pattern library {}: {}",
                            library.getMetadata().getName(),
                            e.getMessage(),
                            e);
                }
            }
        } catch (Exception e) {
            log.error("Error during scheduled pattern library sync check: {}", e.getMessage(), e);
        }
    }

    private boolean needsSync(PatternLibrary library) {
        PatternLibraryStatus status = library.getStatus();

        // Always sync if never synced before
        if (status == null || status.getLastSyncTime() == null) {
            log.debug("Pattern library {} has never been synced", library.getMetadata().getName());
            return true;
        }

        // Parse refresh interval from spec
        String refreshInterval = library.getSpec().getRefreshInterval();
        if (refreshInterval == null || refreshInterval.trim().isEmpty()) {
            refreshInterval = "1h"; // Default to 1 hour
        }

        try {
            Duration interval = parseRefreshInterval(refreshInterval);
            Instant lastSync = status.getLastSyncTime();
            Instant nextSync = lastSync.plus(interval);

            boolean shouldSync = Instant.now().isAfter(nextSync);
            if (shouldSync) {
                log.debug(
                        "Pattern library {} needs sync. Last sync: {}, interval: {}, next sync: {}",
                        library.getMetadata().getName(),
                        lastSync,
                        refreshInterval,
                        nextSync);
            }

            return shouldSync;
        } catch (Exception e) {
            log.error(
                    "Error parsing refresh interval '{}' for pattern library {}: {}",
                    refreshInterval,
                    library.getMetadata().getName(),
                    e.getMessage());
            // Default to 1 hour if parsing fails
            return status.getLastSyncTime().isBefore(Instant.now().minus(1, ChronoUnit.HOURS));
        }
    }

    private Duration parseRefreshInterval(String refreshInterval) {
        // Support formats like: 30s, 5m, 1h, 2h30m, 1d
        refreshInterval = refreshInterval.trim().toLowerCase();

        if (refreshInterval.matches("\\d+s")) {
            return Duration.ofSeconds(Long.parseLong(refreshInterval.replaceAll("s", "")));
        } else if (refreshInterval.matches("\\d+m")) {
            return Duration.ofMinutes(Long.parseLong(refreshInterval.replaceAll("m", "")));
        } else if (refreshInterval.matches("\\d+h")) {
            return Duration.ofHours(Long.parseLong(refreshInterval.replaceAll("h", "")));
        } else if (refreshInterval.matches("\\d+d")) {
            return Duration.ofDays(Long.parseLong(refreshInterval.replaceAll("d", "")));
        }

        // Support compound formats like "1h30m"
        if (refreshInterval.matches("\\d+h\\d+m")) {
            String[] parts = refreshInterval.split("h");
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1].replaceAll("m", ""));
            return Duration.ofHours(hours).plusMinutes(minutes);
        }

        // Default to 1 hour if format is not recognized
        log.warn(
                "Unrecognized refresh interval format: '{}', defaulting to 1 hour",
                refreshInterval);
        return Duration.ofHours(1);
    }

    private void syncPatternLibrary(PatternLibrary library) {
        try {
            log.info(
                    "Starting scheduled sync for pattern library: {}",
                    library.getMetadata().getName());

            // Update status to indicate sync is starting
            updatePatternLibraryStatus(library, "Syncing", "Scheduled sync in progress");

            // Sync each configured repository
            List<PatternRepository> repositories = library.getSpec().getRepositories();
            if (repositories != null) {
                for (PatternRepository repo : repositories) {
                    syncRepository(library, repo);
                }
            }

            // Discover available libraries from synced repositories
            List<String> availableLibraries =
                    patternSyncService.getAvailableLibraries(library.getMetadata().getName());

            // Update status with success
            library.getStatus().setAvailableLibraries(availableLibraries);
            updatePatternLibraryStatus(
                    library,
                    "Ready",
                    String.format(
                            "Scheduled sync completed successfully at %s. %d repositories, %d libraries available",
                            Instant.now().toString(),
                            repositories != null ? repositories.size() : 0,
                            availableLibraries.size()));

            log.info(
                    "Scheduled sync completed successfully for pattern library: {}",
                    library.getMetadata().getName());

        } catch (Exception e) {
            log.error(
                    "Error during scheduled sync for pattern library {}: {}",
                    library.getMetadata().getName(),
                    e.getMessage(),
                    e);
            updatePatternLibraryStatus(
                    library, "Failed", "Scheduled sync failed: " + e.getMessage());
        }
    }

    private void syncRepository(PatternLibrary library, PatternRepository repo) {
        try {
            log.info(
                    "Syncing repository {} for pattern library {}",
                    repo.getName(),
                    library.getMetadata().getName());

            // Get credentials from secret if specified
            String credentials = null;
            if (repo.getCredentials() != null && repo.getCredentials().getSecretRef() != null) {
                credentials = getCredentialsFromSecret(repo.getCredentials().getSecretRef());
            }

            // Sync repository using PatternSyncService
            patternSyncService.syncRepository(library.getMetadata().getName(), repo, credentials);

        } catch (Exception e) {
            log.error(
                    "Failed to sync repository {} during scheduled sync: {}",
                    repo.getName(),
                    e.getMessage(),
                    e);
            throw e; // Re-throw to be handled by calling method
        }
    }

    private String getCredentialsFromSecret(String secretName) {
        try {
            // Get secret from podmortem-system namespace
            var secret =
                    client.secrets().inNamespace("podmortem-system").withName(secretName).get();

            if (secret != null && secret.getData() != null) {
                // Return base64 decoded credentials
                String tokenBase64 = secret.getData().get("token");
                if (tokenBase64 != null) {
                    return new String(java.util.Base64.getDecoder().decode(tokenBase64));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get credentials from secret {}: {}", secretName, e.getMessage());
        }
        return null;
    }

    private void updatePatternLibraryStatus(PatternLibrary library, String phase, String message) {
        try {
            PatternLibraryStatus status = library.getStatus();
            if (status == null) {
                status = new PatternLibraryStatus();
                library.setStatus(status);
            }

            status.setPhase(phase);
            status.setMessage(message);
            status.setLastSyncTime(Instant.now());
            status.setObservedGeneration(library.getMetadata().getGeneration());

            // Update the resource in Kubernetes
            client.resources(PatternLibrary.class)
                    .inNamespace(library.getMetadata().getNamespace())
                    .withName(library.getMetadata().getName())
                    .patchStatus(library);

        } catch (Exception e) {
            log.error("Failed to update pattern library status: {}", e.getMessage(), e);
        }
    }
}
