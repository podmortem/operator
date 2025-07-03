package com.redhat.podmortem.operator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import com.redhat.podmortem.common.model.kube.podmortem.PodmortemStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ScheduledPodFailureMonitor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPodFailureMonitor.class);

    @Inject KubernetesClient client;

    @Inject LogParserClient logParserClient;

    @Inject AIInterfaceClient aiInterfaceClient;

    private final ObjectMapper objectMapper;

    // Track processed pod failures to avoid reprocessing
    // Key: namespace/podName, Value: failure timestamp
    private final Map<String, Instant> processedFailures = new ConcurrentHashMap<>();

    public ScheduledPodFailureMonitor() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Run periodically to check for new pod failures Interval is configurable via
     * pod.failure.check.interval property
     */
    @Scheduled(every = "${pod.failure.check.interval}")
    public void checkForPodFailures() {
        log.debug("Checking for new pod failures");

        try {
            // Get all Podmortem resources from all namespaces
            List<Podmortem> podmortemResources =
                    client.resources(Podmortem.class).inAnyNamespace().list().getItems();

            for (Podmortem podmortem : podmortemResources) {
                try {
                    checkPodmortemForFailures(podmortem);
                } catch (Exception e) {
                    log.error(
                            "Error checking pod failures for podmortem {}: {}",
                            podmortem.getMetadata().getName(),
                            e.getMessage(),
                            e);
                }
            }

            // Clean up old processed failures (older than 24 hours)
            cleanupOldProcessedFailures();

        } catch (Exception e) {
            log.error("Error during scheduled pod failure check: {}", e.getMessage(), e);
        }
    }

    private void checkPodmortemForFailures(Podmortem podmortem) {
        log.debug("Checking pod failures for podmortem: {}", podmortem.getMetadata().getName());

        try {
            // Find pods matching the selector
            List<Pod> pods = findMatchingPods(podmortem);

            // Check each pod for failures
            for (Pod pod : pods) {
                if (hasPodFailed(pod) && !hasBeenProcessed(pod)) {
                    log.info(
                            "Found new pod failure: {} in namespace {}",
                            pod.getMetadata().getName(),
                            pod.getMetadata().getNamespace());

                    // Mark as processed immediately to avoid race conditions
                    markAsProcessed(pod);

                    // Process the failure
                    processPodFailure(podmortem, pod);
                }
            }

        } catch (Exception e) {
            log.error(
                    "Error checking pods for podmortem {}: {}",
                    podmortem.getMetadata().getName(),
                    e.getMessage(),
                    e);
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
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return false;
        }

        return pod.getStatus().getContainerStatuses().stream()
                .anyMatch(
                        containerStatus ->
                                containerStatus.getState() != null
                                        && containerStatus.getState().getTerminated() != null
                                        && containerStatus.getState().getTerminated().getExitCode()
                                                != 0);
    }

    private boolean hasBeenProcessed(Pod pod) {
        String key = getFailureKey(pod);
        Instant failureTime = getFailureTime(pod);

        Instant lastProcessed = processedFailures.get(key);

        // If we've never processed this pod, it's new
        if (lastProcessed == null) {
            return false;
        }

        // If the failure time is after the last processed time, it's a new failure
        return !failureTime.isAfter(lastProcessed);
    }

    private void markAsProcessed(Pod pod) {
        String key = getFailureKey(pod);
        Instant failureTime = getFailureTime(pod);
        processedFailures.put(key, failureTime);

        log.debug("Marked pod failure as processed: {} at {}", key, failureTime);
    }

    private String getFailureKey(Pod pod) {
        return pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName();
    }

    private Instant getFailureTime(Pod pod) {
        // Get the most recent failure time from container statuses
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            return pod.getStatus().getContainerStatuses().stream()
                    .filter(cs -> cs.getState() != null && cs.getState().getTerminated() != null)
                    .filter(cs -> cs.getState().getTerminated().getExitCode() != 0)
                    .map(
                            cs -> {
                                String finishedAt = cs.getState().getTerminated().getFinishedAt();
                                if (finishedAt != null && !finishedAt.isEmpty()) {
                                    try {
                                        return Instant.parse(finishedAt);
                                    } catch (Exception e) {
                                        log.warn("Failed to parse finishedAt time: {}", finishedAt);
                                    }
                                }
                                return Instant.now(); // Fallback to current time
                            })
                    .max(Instant::compareTo)
                    .orElse(Instant.now());
        }

        return Instant.now();
    }

    private void cleanupOldProcessedFailures() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        processedFailures.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));

        log.debug(
                "Cleaned up old processed failures, {} entries remaining",
                processedFailures.size());
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

            // Get AI provider
            Optional<AIProvider> aiProvider = getAIProvider(resource);

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
                                                    + aiResponse.getExplanation());
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
                        resource, pod, "Pattern analysis completed, AI provider not found");
            }
        } else {
            // Only pattern analysis, no AI
            updatePodFailureStatus(resource, pod, "Pattern analysis completed");
        }
    }

    private Optional<AIProvider> getAIProvider(Podmortem resource) {
        if (resource.getSpec().getAiProviderRef() == null) {
            return Optional.empty();
        }

        String providerName = resource.getSpec().getAiProviderRef().getName();
        String providerNamespace = resource.getSpec().getAiProviderRef().getNamespace();

        // Default to same namespace if not specified
        if (providerNamespace == null) {
            providerNamespace = resource.getMetadata().getNamespace();
        }

        try {
            AIProvider aiProvider =
                    client.resources(AIProvider.class)
                            .inNamespace(providerNamespace)
                            .withName(providerName)
                            .get();

            return Optional.ofNullable(aiProvider);
        } catch (Exception e) {
            log.error("Error fetching AI provider: {}/{}", providerNamespace, providerName, e);
            return Optional.empty();
        }
    }

    private void updatePodFailureStatus(Podmortem resource, Pod pod, String message) {
        try {
            log.info("Updated status for pod {}: {}", pod.getMetadata().getName(), message);

            // Update the Podmortem status
            PodmortemStatus status = resource.getStatus();
            if (status == null) {
                status = new PodmortemStatus();
                resource.setStatus(status);
            }

            status.setPhase("Processing");
            status.setMessage(String.format("Pod %s: %s", pod.getMetadata().getName(), message));
            status.setLastUpdate(Instant.now());

            // Update the resource in Kubernetes
            client.resources(Podmortem.class)
                    .inNamespace(resource.getMetadata().getNamespace())
                    .withName(resource.getMetadata().getName())
                    .patchStatus(resource);

        } catch (Exception e) {
            log.error("Failed to update podmortem status: {}", e.getMessage(), e);
        }
    }
}
