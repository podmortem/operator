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
import java.time.Instant;
import java.util.List;
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

            // Update status with available libraries
            resource.getStatus().setAvailableLibraries(availableLibraries);
            updatePatternLibraryStatus(
                    resource,
                    "Ready",
                    String.format(
                            "Successfully synced %d repositories, %d libraries available",
                            repositories != null ? repositories.size() : 0,
                            availableLibraries.size()));

            return UpdateControl.patchStatus(resource);

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
}
