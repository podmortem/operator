package com.redhat.podmortem.operator.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import com.redhat.podmortem.common.model.kube.podmortem.PodmortemStatus;
import com.redhat.podmortem.operator.service.AIInterfaceClient;
import com.redhat.podmortem.operator.service.EventService;
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

/**
 * Kubernetes operator reconciler for managing Podmortem Custom Resources.
 *
 * <p>Reconciles Podmortem resources by monitoring pods that match the configured selector,
 * detecting failures, collecting diagnostic data, and orchestrating analysis workflows through the
 * log parser and AI interface services.
 */
@ControllerConfiguration
@ApplicationScoped
public class PodmortemReconciler implements Reconciler<Podmortem> {

    private static final Logger log = LoggerFactory.getLogger(PodmortemReconciler.class);

    @Inject KubernetesClient client;

    @Inject LogParserClient logParserClient;

    @Inject AIInterfaceClient aiInterfaceClient;

    @Inject EventService eventService;

    private final ObjectMapper objectMapper;

    /** Creates a new PodmortemReconciler with configured JSON mapper. */
    public PodmortemReconciler() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Reconciles a Podmortem resource by monitoring matching pods for failures.
     *
     * <p>Performs pod failure detection, data collection, and initiates analysis workflows. Updates
     * the resource status to reflect the current monitoring state and any failures detected during
     * the reconciliation cycle.
     *
     * @param resource the Podmortem resource being reconciled
     * @param context the reconciliation context containing additional information
     * @return an UpdateControl indicating the desired reconciliation outcome
     */
    @Override
    public UpdateControl<Podmortem> reconcile(Podmortem resource, Context<Podmortem> context) {
        log.info("Reconciling Podmortem: {}", resource.getMetadata().getName());

        if (resource.getStatus() == null) {
            resource.setStatus(new PodmortemStatus());
        }

        try {
            List<Pod> pods = findMatchingPods(resource);

            for (Pod pod : pods) {
                if (hasPodFailed(pod)) {
                    processPodFailure(resource, pod);
                }
            }

            updatePodmortemStatus(resource, "Ready", "Monitoring pods for failures");

            return UpdateControl.patchStatus(resource);

        } catch (Exception e) {
            log.error("Error reconciling Podmortem: {}", resource.getMetadata().getName(), e);
            updatePodmortemStatus(resource, "Error", "Failed to reconcile: " + e.getMessage());
            return UpdateControl.patchStatus(resource);
        }
    }

    /**
     * Finds pods that match the Podmortem resource's label selector.
     *
     * @param resource the Podmortem resource containing the pod selector
     * @return a list of pods matching the selector across all namespaces
     */
    private List<Pod> findMatchingPods(Podmortem resource) {
        return client.pods()
                .inAnyNamespace()
                .withLabelSelector(resource.getSpec().getPodSelector())
                .list()
                .getItems();
    }

    /**
     * Determines if a pod has failed by checking container exit codes.
     *
     * <p>A pod is considered failed if any of its containers terminated with a non-zero exit code.
     *
     * @param pod the pod to check for failure
     * @return true if the pod has failed, false otherwise
     */
    private boolean hasPodFailed(Pod pod) {
        return pod.getStatus().getContainerStatuses().stream()
                .anyMatch(
                        containerStatus ->
                                containerStatus.getState().getTerminated() != null
                                        && containerStatus.getState().getTerminated().getExitCode()
                                                != 0);
    }

    /**
     * Processes a pod failure by collecting diagnostic data and initiating analysis.
     *
     * <p>Collects pod logs and events, sends data to the log parser for pattern analysis, and
     * optionally to the AI interface for explanation generation if enabled.
     *
     * @param resource the Podmortem resource managing this pod
     * @param pod the failed pod to process
     */
    private void processPodFailure(Podmortem resource, Pod pod) {
        log.info("Processing pod failure for pod: {}", pod.getMetadata().getName());
        eventService.emitFailureDetected(pod, resource);

        try {
            PodFailureData failureData = collectPodFailureData(pod);

            logParserClient
                    .analyzeLog(failureData)
                    .subscribe()
                    .with(
                            analysisResult -> {
                                log.debug(
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
                                eventService.emitAnalysisError(
                                        pod, resource, "Analysis failed: " + failure.getMessage());
                            });

        } catch (Exception e) {
            log.error("Error processing pod failure for pod: {}", pod.getMetadata().getName(), e);
            updatePodFailureStatus(resource, pod, "Processing failed: " + e.getMessage());
            eventService.emitAnalysisError(pod, resource, "Processing failed: " + e.getMessage());
        }
    }

    /**
     * Collects comprehensive failure data for a pod including logs and events.
     *
     * @param pod the failed pod to collect data from
     * @return a PodFailureData object containing all relevant diagnostic information
     */
    private PodFailureData collectPodFailureData(Pod pod) {
        String podLogs =
                client.pods()
                        .inNamespace(pod.getMetadata().getNamespace())
                        .withName(pod.getMetadata().getName())
                        .getLog();

        List<Event> events =
                client.v1()
                        .events()
                        .inNamespace(pod.getMetadata().getNamespace())
                        .withField("involvedObject.name", pod.getMetadata().getName())
                        .list()
                        .getItems();

        return new PodFailureData(pod, podLogs, events);
    }

    /**
     * Handles analysis results from the log parser and optionally requests AI analysis text.
     *
     * <p>If AI analysis is enabled and an AI provider is configured, forwards the analysis results
     * to the AI interface for explanation generation. Otherwise, updates the status to indicate
     * completion of pattern analysis only.
     *
     * @param resource the Podmortem resource managing this analysis
     * @param pod the pod that was analyzed
     * @param analysisResult the pattern analysis results from the log parser
     */
    private void handleAnalysisResult(Podmortem resource, Pod pod, AnalysisResult analysisResult) {
        log.debug("Handling analysis result for pod: {}", pod.getMetadata().getName());

        if (Boolean.TRUE.equals(resource.getSpec().getAiAnalysisEnabled())
                && resource.getSpec().getAiProviderRef() != null) {

            getAIProviderAsync(resource)
                    .subscribe()
                    .with(
                            aiProvider -> {
                                if (aiProvider.isPresent()) {
                                    aiInterfaceClient
                                            .generateExplanation(analysisResult, aiProvider.get())
                                            .subscribe()
                                            .with(
                                                    aiResponse -> {
                                                        log.debug(
                                                                "AI analysis completed for pod: {}",
                                                                pod.getMetadata().getName());
                                                        updatePodFailureStatus(
                                                                resource,
                                                                pod,
                                                                "Analysis completed with AI analysis: "
                                                                        + aiResponse
                                                                                .getExplanation());
                                                        eventService.emitAnalysisComplete(
                                                                pod,
                                                                resource,
                                                                analysisResult,
                                                                aiResponse.getExplanation());
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
                                                        eventService.emitAnalysisComplete(
                                                                pod,
                                                                resource,
                                                                analysisResult,
                                                                "AI analysis failed: "
                                                                        + failure.getMessage());
                                                        eventService.emitAnalysisError(
                                                                pod,
                                                                resource,
                                                                "AI analysis failed: "
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
                                    eventService.emitAnalysisComplete(
                                            pod, resource, analysisResult, "AI provider not found");
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
                                eventService.emitAnalysisComplete(
                                        pod, resource, analysisResult, "AI provider lookup failed");
                            });
        } else {
            updatePodFailureStatus(resource, pod, "Pattern analysis completed");
            eventService.emitAnalysisComplete(pod, resource, analysisResult, "AI disabled");
        }
    }

    /**
     * Asynchronously retrieves the AI provider referenced by the Podmortem resource.
     *
     * <p>Executes the Kubernetes API call on a worker thread to avoid blocking the event loop.
     * Handles namespace defaulting and error recovery.
     *
     * @param resource the Podmortem resource containing the AI provider reference
     * @return a Uni that emits an Optional containing the AI provider if found
     */
    private io.smallrye.mutiny.Uni<Optional<AIProvider>> getAIProviderAsync(Podmortem resource) {
        if (resource.getSpec().getAiProviderRef() == null) {
            return io.smallrye.mutiny.Uni.createFrom().item(Optional.empty());
        }

        return io.smallrye.mutiny.Uni.createFrom()
                .item(
                        () -> {
                            try {
                                String providerName =
                                        resource.getSpec().getAiProviderRef().getName();
                                String providerNamespace =
                                        resource.getSpec().getAiProviderRef().getNamespace();

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

    /**
     * Updates the overall status of a Podmortem resource.
     *
     * @param resource the Podmortem resource to update
     * @param phase the current operational phase
     * @param message a human-readable status message
     */
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

    /**
     * Updates the status for a specific pod failure within a Podmortem resource.
     *
     * @param resource the Podmortem resource to update
     * @param pod the pod that was processed
     * @param message the status message for this pod failure
     */
    private void updatePodFailureStatus(Podmortem resource, Pod pod, String message) {
        log.debug("Updated status for pod {}: {}", pod.getMetadata().getName(), message);
        updatePodmortemStatus(
                resource,
                "Processing",
                String.format("Pod %s: %s", pod.getMetadata().getName(), message));
    }
}
