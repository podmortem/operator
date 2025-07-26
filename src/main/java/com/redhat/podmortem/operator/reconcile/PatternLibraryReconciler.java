package com.redhat.podmortem.operator.reconcile;

import com.redhat.podmortem.common.model.kube.patternlibrary.PatternLibrary;
import com.redhat.podmortem.common.model.kube.patternlibrary.PatternLibraryStatus;
import com.redhat.podmortem.common.model.kube.patternlibrary.PatternRepository;
import com.redhat.podmortem.operator.service.PatternSyncService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ControllerConfiguration
@ApplicationScoped
public class PatternLibraryReconciler implements Reconciler<PatternLibrary> {

    private static final Logger log = LoggerFactory.getLogger(PatternLibraryReconciler.class);

    @Inject KubernetesClient client;

    @Inject PatternSyncService patternSyncService;

    @Override
    public UpdateControl<PatternLibrary> reconcile(
            PatternLibrary resource, Context<PatternLibrary> context) {
        log.info("Reconciling PatternLibrary: {}", resource.getMetadata().getName());

        // Initialize status if not present
        if (resource.getStatus() == null) {
            resource.setStatus(new PatternLibraryStatus());
        }

        try {
            // Check if sync is needed based on refresh interval
            if (!needsSync(resource)) {
                log.debug(
                        "PatternLibrary {} does not need sync yet",
                        resource.getMetadata().getName());
                return UpdateControl.noUpdate();
            }

            log.info("Sync triggered for PatternLibrary: {}", resource.getMetadata().getName());

            // Update status to syncing
            updatePatternLibraryStatus(resource, "Syncing", "Synchronizing pattern repositories");

            // Sync each configured repository
            List<PatternRepository> repositories = resource.getSpec().getRepositories();
            if (repositories != null) {
                for (PatternRepository repo : repositories) {
                    syncRepository(resource, repo);
                }
            }

            // Discover available libraries from synced repositories
            List<String> availableLibraries =
                    patternSyncService.getAvailableLibraries(resource.getMetadata().getName());

            // Update status with available libraries and sync time
            resource.getStatus().setAvailableLibraries(availableLibraries);
            resource.getStatus().setLastSyncTime(Instant.now());

            updatePatternLibraryStatus(
                    resource,
                    "Ready",
                    String.format(
                            "Sync completed: %d repositories, %d libraries available",
                            repositories != null ? repositories.size() : 0,
                            availableLibraries.size()));

            // Schedule next reconciliation based on refresh interval
            return UpdateControl.patchStatus(resource)
                    .rescheduleAfter(getRefreshInterval(resource), TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Error reconciling PatternLibrary: {}", resource.getMetadata().getName(), e);
            updatePatternLibraryStatus(
                    resource, "Failed", "Failed to reconcile: " + e.getMessage());
            return UpdateControl.patchStatus(resource);
        }
    }

    private void syncRepository(PatternLibrary resource, PatternRepository repo) {
        try {
            log.info(
                    "Syncing repository {} for PatternLibrary {}",
                    repo.getName(),
                    resource.getMetadata().getName());

            // Get credentials from secret if specified
            String credentials = null;
            if (repo.getCredentials() != null && repo.getCredentials().getSecretRef() != null) {
                credentials = getCredentialsFromSecret(repo.getCredentials().getSecretRef());
            }

            // Sync repository using PatternSyncService
            patternSyncService.syncRepository(resource.getMetadata().getName(), repo, credentials);

            // Update repository sync status
            updateRepositoryStatus(resource, repo, "Success", null);

        } catch (Exception e) {
            log.error("Failed to sync repository {}: {}", repo.getName(), e.getMessage(), e);
            updateRepositoryStatus(resource, repo, "Failed", e.getMessage());
        }
    }

    private String getCredentialsFromSecret(String secretName) {
        try {
            // Get secret from the same namespace as PatternLibrary
            var secret =
                    client.secrets()
                            .inNamespace("podmortem-system") // TODO: use resource namespace
                            .withName(secretName)
                            .get();

            if (secret != null && secret.getData() != null) {
                // Return base64 decoded credentials
                // This is a simplified version - in practice you'd handle username/password
                // or token-based auth
                return new String(secret.getData().get("token"));
            }
        } catch (Exception e) {
            log.warn("Failed to get credentials from secret {}: {}", secretName, e.getMessage());
        }
        return null;
    }

    private void updateRepositoryStatus(
            PatternLibrary resource, PatternRepository repo, String status, String error) {
        // TODO: Implement repository status tracking in PatternLibraryStatus
        // This would update the syncedRepositories field with individual repo status
        log.info("Repository {} status: {}", repo.getName(), status);
    }

    private void updatePatternLibraryStatus(PatternLibrary resource, String phase, String message) {
        PatternLibraryStatus status = resource.getStatus();
        if (status == null) {
            status = new PatternLibraryStatus();
            resource.setStatus(status);
        }

        status.setPhase(phase);
        status.setMessage(message);
        status.setLastSyncTime(Instant.now());
        status.setObservedGeneration(resource.getMetadata().getGeneration());
    }

    /** Check if the pattern library needs to be synced based on refresh interval */
    private boolean needsSync(PatternLibrary library) {
        PatternLibraryStatus status = library.getStatus();

        // Always sync if never synced before
        if (status == null || status.getLastSyncTime() == null) {
            log.debug("PatternLibrary {} has never been synced", library.getMetadata().getName());
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
                        "PatternLibrary {} needs sync. Last sync: {}, interval: {}, next sync: {}",
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
            return status.getLastSyncTime().isBefore(Instant.now().minus(Duration.ofHours(1)));
        }
    }

    /** Get the refresh interval in seconds for rescheduling */
    private long getRefreshInterval(PatternLibrary library) {
        String refreshInterval = library.getSpec().getRefreshInterval();
        if (refreshInterval == null || refreshInterval.trim().isEmpty()) {
            refreshInterval = "1h"; // Default to 1 hour
        }

        try {
            Duration interval = parseRefreshInterval(refreshInterval);
            return interval.getSeconds();
        } catch (Exception e) {
            log.error(
                    "Error parsing refresh interval '{}' for pattern library {}: {}",
                    refreshInterval,
                    library.getMetadata().getName(),
                    e.getMessage());
            return 3600; // Default to 1 hour in seconds
        }
    }

    /** Parse refresh interval string into Duration */
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
}
