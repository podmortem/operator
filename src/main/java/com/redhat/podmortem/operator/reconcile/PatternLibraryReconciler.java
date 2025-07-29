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

/**
 * Kubernetes operator reconciler for managing PatternLibrary Custom Resources.
 *
 * <p>Reconciles PatternLibrary resources by synchronizing pattern definitions from external Git
 * repositories, managing refresh schedules, and tracking available pattern libraries for use by log
 * parsing services.
 */
@ControllerConfiguration
@ApplicationScoped
public class PatternLibraryReconciler implements Reconciler<PatternLibrary> {

    private static final Logger log = LoggerFactory.getLogger(PatternLibraryReconciler.class);

    @Inject KubernetesClient client;

    @Inject PatternSyncService patternSyncService;

    /**
     * Reconciles a PatternLibrary resource by synchronizing configured repositories.
     *
     * <p>Checks if synchronization is needed based on the refresh interval, syncs all configured
     * repositories, discovers available pattern libraries, and schedules the next reconciliation.
     *
     * @param resource the PatternLibrary resource being reconciled
     * @param context the reconciliation context containing additional information
     * @return an UpdateControl indicating the desired reconciliation outcome
     */
    @Override
    public UpdateControl<PatternLibrary> reconcile(
            PatternLibrary resource, Context<PatternLibrary> context) {
        log.info("Reconciling PatternLibrary: {}", resource.getMetadata().getName());

        // init status if not present
        if (resource.getStatus() == null) {
            resource.setStatus(new PatternLibraryStatus());
        }

        try {
            // check if sync is needed based on refresh interval
            if (!needsSync(resource)) {
                log.debug(
                        "PatternLibrary {} does not need sync yet",
                        resource.getMetadata().getName());
                return UpdateControl.noUpdate();
            }

            log.info("Sync triggered for PatternLibrary: {}", resource.getMetadata().getName());
            updatePatternLibraryStatus(resource, "Syncing", "Synchronizing pattern repositories");

            // sync each configured repository
            List<PatternRepository> repositories = resource.getSpec().getRepositories();
            if (repositories != null) {
                for (PatternRepository repo : repositories) {
                    syncRepository(resource, repo);
                }
            }

            // discover available libraries from synced repositories
            List<String> availableLibraries =
                    patternSyncService.getAvailableLibraries(resource.getMetadata().getName());

            resource.getStatus().setAvailableLibraries(availableLibraries);
            resource.getStatus().setLastSyncTime(Instant.now());

            updatePatternLibraryStatus(
                    resource,
                    "Ready",
                    String.format(
                            "Sync completed: %d repositories, %d libraries available",
                            repositories != null ? repositories.size() : 0,
                            availableLibraries.size()));

            // schedule next reconciliation based on refresh interval
            return UpdateControl.patchStatus(resource)
                    .rescheduleAfter(getRefreshInterval(resource), TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Error reconciling PatternLibrary: {}", resource.getMetadata().getName(), e);
            updatePatternLibraryStatus(
                    resource, "Failed", "Failed to reconcile: " + e.getMessage());
            return UpdateControl.patchStatus(resource);
        }
    }

    /**
     * Synchronizes a single pattern repository with authentication support.
     *
     * <p>Handles credential extraction from Kubernetes secrets and delegates the actual Git
     * operations to the PatternSyncService.
     *
     * @param resource the PatternLibrary resource being synced
     * @param repo the repository configuration to synchronize
     */
    private void syncRepository(PatternLibrary resource, PatternRepository repo) {
        try {
            log.info(
                    "Syncing repository {} for PatternLibrary {}",
                    repo.getName(),
                    resource.getMetadata().getName());

            // get creds
            String credentials = null;
            if (repo.getCredentials() != null && repo.getCredentials().getSecretRef() != null) {
                credentials = getCredentialsFromSecret(repo.getCredentials().getSecretRef());
            }

            patternSyncService.syncRepository(resource.getMetadata().getName(), repo, credentials);
            updateRepositoryStatus(resource, repo, "Success", null);

        } catch (Exception e) {
            log.error("Failed to sync repository {}: {}", repo.getName(), e.getMessage(), e);
            updateRepositoryStatus(resource, repo, "Failed", e.getMessage());
        }
    }

    /**
     * Retrieves Git credentials from a Kubernetes secret.
     *
     * <p>Extracts authentication tokens from secret data for accessing private repositories.
     * Supports base64 encoded secret values.
     *
     * @param secretName the name of the secret containing credentials
     * @return the decoded credentials string, or null if not found
     */
    private String getCredentialsFromSecret(String secretName) {
        try {
            var secret =
                    client.secrets()
                            .inNamespace("podmortem-system") // TODO: use resource namespace
                            .withName(secretName)
                            .get();

            if (secret != null && secret.getData() != null) {
                // TODO: Handle different auth types (username/password, tokens, etc.)
                return new String(secret.getData().get("token"));
            }
        } catch (Exception e) {
            log.warn("Failed to get credentials from secret {}: {}", secretName, e.getMessage());
        }
        return null;
    }

    /**
     * Updates the status for a specific repository within the PatternLibrary.
     *
     * @param resource the PatternLibrary resource being updated
     * @param repo the repository that was processed
     * @param status the sync status (Success/Failed)
     * @param error optional error message if sync failed
     */
    private void updateRepositoryStatus(
            PatternLibrary resource, PatternRepository repo, String status, String error) {
        // TODO: Implement repository status tracking in PatternLibraryStatus
        // This would update the syncedRepositories field with individual repo status
        log.info("Repository {} status: {}", repo.getName(), status);
    }

    /**
     * Updates the overall status of a PatternLibrary resource.
     *
     * @param resource the PatternLibrary resource to update
     * @param phase the current operational phase
     * @param message a human-readable status message
     */
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

    /**
     * Determines if the pattern library needs synchronization based on refresh interval.
     *
     * <p>Compares the last sync time with the configured refresh interval to determine if it's time
     * for another synchronization cycle.
     *
     * @param library the PatternLibrary resource to check
     * @return true if synchronization is needed, false otherwise
     */
    private boolean needsSync(PatternLibrary library) {
        PatternLibraryStatus status = library.getStatus();

        // Always sync if never synced before
        if (status == null || status.getLastSyncTime() == null) {
            log.debug("PatternLibrary {} has never been synced", library.getMetadata().getName());
            return true;
        }

        String refreshInterval = library.getSpec().getRefreshInterval();
        if (refreshInterval == null || refreshInterval.trim().isEmpty()) {
            refreshInterval = "1h";
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
            return status.getLastSyncTime().isBefore(Instant.now().minus(Duration.ofHours(1)));
        }
    }

    /**
     * Gets the refresh interval in seconds for scheduling the next reconciliation.
     *
     * @param library the PatternLibrary resource containing the refresh interval
     * @return the refresh interval in seconds
     */
    private long getRefreshInterval(PatternLibrary library) {
        String refreshInterval = library.getSpec().getRefreshInterval();
        if (refreshInterval == null || refreshInterval.trim().isEmpty()) {
            refreshInterval = "1h";
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
            return 3600;
        }
    }

    /**
     * Parses a human-readable refresh interval string into a Duration.
     *
     * <p>Supports various time format strings including: - Simple formats: "30s", "5m", "1h", "2d"
     * - Compound formats: "1h30m", "2h15m"
     *
     * @param refreshInterval the interval string to parse
     * @return a Duration representing the parsed interval
     * @throws IllegalArgumentException if the format is not recognized
     */
    private Duration parseRefreshInterval(String refreshInterval) {
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

        if (refreshInterval.matches("\\d+h\\d+m")) {
            String[] parts = refreshInterval.split("h");
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1].replaceAll("m", ""));
            return Duration.ofHours(hours).plusMinutes(minutes);
        }

        log.warn(
                "Unrecognized refresh interval format: '{}', defaulting to 1 hour",
                refreshInterval);
        return Duration.ofHours(1);
    }
}
