package com.redhat.podmortem.operator.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import com.redhat.podmortem.common.model.kube.podmortem.PodmortemStatus;
import com.redhat.podmortem.operator.service.AIInterfaceClient;
import com.redhat.podmortem.operator.service.LogParserClient;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ControllerConfiguration
@ApplicationScoped
public class PodmortemReconciler implements Reconciler<Podmortem> {

    private static final Logger log = LoggerFactory.getLogger(PodmortemReconciler.class);

    @Inject KubernetesClient client;

    @Inject LogParserClient logParserClient;

    @Inject AIInterfaceClient aiInterfaceClient;

    private final ObjectMapper objectMapper;

    public PodmortemReconciler() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public UpdateControl<Podmortem> reconcile(Podmortem resource, Context<Podmortem> context) {
        log.info("Reconciling Podmortem: {}", resource.getMetadata().getName());

        // Initialize status if not present
        if (resource.getStatus() == null) {
            resource.setStatus(new PodmortemStatus());
        }

        try {
            // Find pods matching the selector
            List<Pod> pods = findMatchingPods(resource);

            // Process each pod for failures
            for (Pod pod : pods) {
                if (hasPodFailed(pod)) {
                    processPodFailure(resource, pod);
                }
            }

            // Update status
            updatePodmortemStatus(resource, "Ready", "Monitoring pods for failures");

            return UpdateControl.patchStatus(resource);

        } catch (Exception e) {
            log.error("Error reconciling Podmortem: {}", resource.getMetadata().getName(), e);
            updatePodmortemStatus(resource, "Error", "Failed to reconcile: " + e.getMessage());
            return UpdateControl.patchStatus(resource);
        }
    }

    private List<Pod> findMatchingPods(Podmortem resource) {
        return client.pods()
                .inAnyNamespace()
                .withLabelSelector(resource.getSpec().getPodSelector())
                .list()
                .getItems();
    }

    private boolean hasPodFailed(Pod pod) {
        return pod.getStatus().getContainerStatuses().stream()
                .anyMatch(
                        containerStatus ->
                                containerStatus.getState().getTerminated() != null
                                        && containerStatus.getState().getTerminated().getExitCode()
                                                != 0);
    }

    private void processPodFailure(Podmortem resource, Pod pod) {
        log.info("Processing pod failure for pod: {}", pod.getMetadata().getName());

        try {
            // Collect failure data
            PodFailureData failureData = collectPodFailureData(pod);

            // Send to log parser for analysis
            logParserClient
                    .analyzeLog(failureData)
                    .subscribe()
                    .with(
                            analysisResult -> {
                                log.info(
                                        "Log analysis completed for pod: {}",
                                        pod.getMetadata().getName());
                                handleAnalysisResult(resource, pod, analysisResult);
                            },
                            failure -> {
                                log.error(
                                        "Log analysis failed for pod: {}",
                                        pod.getMetadata().getName(),
                                        failure);
                                updatePodFailureStatus(
                                        resource, pod, "Analysis failed: " + failure.getMessage());
                            });

        } catch (Exception e) {
            log.error("Error processing pod failure for pod: {}", pod.getMetadata().getName(), e);
            updatePodFailureStatus(resource, pod, "Processing failed: " + e.getMessage());
        }
    }

    private PodFailureData collectPodFailureData(Pod pod) {
        // Get pod logs
        String podLogs =
                client.pods()
                        .inNamespace(pod.getMetadata().getNamespace())
                        .withName(pod.getMetadata().getName())
                        .getLog();

        // Get events for the pod using core API (which supports involvedObject.name field selector)
        List<Event> events =
                client.v1()
                        .events()
                        .inNamespace(pod.getMetadata().getNamespace())
                        .withField("involvedObject.name", pod.getMetadata().getName())
                        .list()
                        .getItems();

        return new PodFailureData(pod, podLogs, events);
    }

    private void handleAnalysisResult(Podmortem resource, Pod pod, AnalysisResult analysisResult) {
        log.info("Handling analysis result for pod: {}", pod.getMetadata().getName());

        // Check if AI analysis is enabled and AI provider is configured
        if (Boolean.TRUE.equals(resource.getSpec().getAiAnalysisEnabled())
                && resource.getSpec().getAiProviderRef() != null) {

            // Use reactive approach to avoid blocking the event loop
            getAIProviderAsync(resource)
                    .subscribe()
                    .with(
                            aiProvider -> {
                                if (aiProvider.isPresent()) {
                                    // Send to AI interface for explanation
                                    aiInterfaceClient
                                            .generateExplanation(analysisResult, aiProvider.get())
                                            .subscribe()
                                            .with(
                                                    aiResponse -> {
                                                        log.info(
                                                                "AI analysis completed for pod: {}",
                                                                pod.getMetadata().getName());
                                                        updatePodFailureStatus(
                                                                resource,
                                                                pod,
                                                                "Analysis completed with AI explanation: "
                                                                        + aiResponse
                                                                                .getExplanation());
                                                    },
                                                    failure -> {
                                                        log.error(
                                                                "AI analysis failed for pod: {}",
                                                                pod.getMetadata().getName(),
                                                                failure);
                                                        updatePodFailureStatus(
                                                                resource,
                                                                pod,
                                                                "Pattern analysis completed, AI analysis failed: "
                                                                        + failure.getMessage());
                                                    });
                                } else {
                                    log.warn(
                                            "AI provider not found for Podmortem: {}",
                                            resource.getMetadata().getName());
                                    updatePodFailureStatus(
                                            resource,
                                            pod,
                                            "Pattern analysis completed, AI provider not found");
                                }
                            },
                            failure -> {
                                log.error(
                                        "Failed to get AI provider for pod: {}",
                                        pod.getMetadata().getName(),
                                        failure);
                                updatePodFailureStatus(
                                        resource,
                                        pod,
                                        "Pattern analysis completed, AI provider lookup failed");
                            });
        } else {
            // Only pattern analysis, no AI
            updatePodFailureStatus(resource, pod, "Pattern analysis completed");
        }
    }

    /** Get AI provider for the Podmortem resource asynchronously */
    private io.smallrye.mutiny.Uni<Optional<AIProvider>> getAIProviderAsync(Podmortem resource) {
        if (resource.getSpec().getAiProviderRef() == null) {
            return io.smallrye.mutiny.Uni.createFrom().item(Optional.empty());
        }

        // Run the blocking Kubernetes API call on a worker thread
        return io.smallrye.mutiny.Uni.createFrom()
                .item(
                        () -> {
                            try {
                                String providerName =
                                        resource.getSpec().getAiProviderRef().getName();
                                String providerNamespace =
                                        resource.getSpec().getAiProviderRef().getNamespace();

                                // Default to same namespace if not specified
                                if (providerNamespace == null) {
                                    providerNamespace = resource.getMetadata().getNamespace();
                                }

                                log.debug(
                                        "Looking up AI provider: {}/{}",
                                        providerNamespace,
                                        providerName);

                                AIProvider aiProvider =
                                        client.resources(AIProvider.class)
                                                .inNamespace(providerNamespace)
                                                .withName(providerName)
                                                .get();

                                if (aiProvider != null) {
                                    log.info(
                                            "Found AI provider: {}/{}",
                                            providerNamespace,
                                            providerName);
                                    return Optional.of(aiProvider);
                                } else {
                                    log.warn(
                                            "AI provider not found: {}/{}",
                                            providerNamespace,
                                            providerName);
                                    return Optional.<AIProvider>empty();
                                }
                            } catch (Exception e) {
                                log.error("Error fetching AI provider: {}", e.getMessage(), e);
                                return Optional.<AIProvider>empty();
                            }
                        })
                .runSubscriptionOn(
                        io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    private void updatePodmortemStatus(Podmortem resource, String phase, String message) {
        PodmortemStatus status = resource.getStatus();
        if (status == null) {
            status = new PodmortemStatus();
            resource.setStatus(status);
        }

        status.setPhase(phase);
        status.setMessage(message);
        status.setLastUpdate(Instant.now());
    }

    private void updatePodFailureStatus(Podmortem resource, Pod pod, String message) {
        log.info("Updated status for pod {}: {}", pod.getMetadata().getName(), message);
        // In a real implementation, you might want to store per-pod failure status
        // For now, we'll just update the overall status
        updatePodmortemStatus(
                resource,
                "Processing",
                String.format("Pod %s: %s", pod.getMetadata().getName(), message));
    }
}
