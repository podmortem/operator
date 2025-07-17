package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import com.redhat.podmortem.common.model.kube.podmortem.PodmortemStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class PodFailureWatcher {

    private static final Logger log = LoggerFactory.getLogger(PodFailureWatcher.class);

    @Inject KubernetesClient client;
    @Inject LogParserClient logParserClient;
    @Inject AIInterfaceClient aiInterfaceClient;

    // Track processed failures to avoid duplicates
    private final Map<String, Instant> processedFailures = new ConcurrentHashMap<>();

    /** Start watching for pod failures on startup */
    public void onStartup(@Observes StartupEvent event) {
        log.info("Starting real-time pod failure watcher");
        startPodWatcher();
    }

    /** Watch all pods in all namespaces for status changes */
    private void startPodWatcher() {
        client.pods()
                .inAnyNamespace()
                .watch(
                        new Watcher<Pod>() {
                            @Override
                            public void eventReceived(Action action, Pod pod) {
                                try {
                                    // Only process MODIFIED events where pod has failed
                                    if (action == Action.MODIFIED && hasPodFailed(pod)) {
                                        handlePodFailure(pod);
                                    }
                                } catch (Exception e) {
                                    log.error(
                                            "Error processing pod event for {}: {}",
                                            pod.getMetadata().getName(),
                                            e.getMessage(),
                                            e);
                                }
                            }

                            @Override
                            public void onClose(WatcherException cause) {
                                if (cause != null) {
                                    log.error(
                                            "Pod watcher closed due to error: {}",
                                            cause.getMessage(),
                                            cause);
                                    // Restart the watcher after a delay
                                    restartWatcher();
                                } else {
                                    log.info("Pod watcher closed normally");
                                }
                            }
                        });
    }

    /** Check if a pod has failed */
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

    /** Handle a pod failure event */
    private void handlePodFailure(Pod pod) {
        String podKey = pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName();

        // Check if we've already processed this failure
        Instant failureTime = getFailureTime(pod);
        if (failureTime != null && processedFailures.containsKey(podKey)) {
            Instant previousFailure = processedFailures.get(podKey);
            if (failureTime.equals(previousFailure)) {
                log.debug("Already processed failure for pod: {}", podKey);
                return;
            }
        }

        log.info("Pod failure detected: {}", podKey);

        // Mark as processed
        if (failureTime != null) {
            processedFailures.put(podKey, failureTime);
        }

        // Find matching Podmortem resources
        List<Podmortem> podmortemResources = findMatchingPodmortemResources(pod);

        for (Podmortem podmortem : podmortemResources) {
            processPodFailureForPodmortem(podmortem, pod);
        }
    }

    /** Get the failure timestamp from the pod */
    private Instant getFailureTime(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return null;
        }

        return pod.getStatus().getContainerStatuses().stream()
                .filter(cs -> cs.getState() != null && cs.getState().getTerminated() != null)
                .map(cs -> cs.getState().getTerminated().getFinishedAt())
                .filter(timestamp -> timestamp != null)
                .map(timestamp -> Instant.parse(timestamp))
                .findFirst()
                .orElse(null);
    }

    /** Find Podmortem resources that match this pod */
    private List<Podmortem> findMatchingPodmortemResources(Pod pod) {
        List<Podmortem> allPodmortem =
                client.resources(Podmortem.class).inAnyNamespace().list().getItems();

        return allPodmortem.stream()
                .filter(podmortem -> podMatchesSelector(pod, podmortem))
                .toList();
    }

    /** Check if a pod matches the Podmortem selector */
    private boolean podMatchesSelector(Pod pod, Podmortem podmortem) {
        if (podmortem.getSpec() == null || podmortem.getSpec().getPodSelector() == null) {
            return false;
        }

        Map<String, String> selector = podmortem.getSpec().getPodSelector().getMatchLabels();
        if (selector == null || selector.isEmpty()) {
            return false;
        }

        Map<String, String> podLabels = pod.getMetadata().getLabels();
        if (podLabels == null) {
            return false;
        }

        // Check if all selector labels match pod labels
        return selector.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(podLabels.get(entry.getKey())));
    }

    /** Process the pod failure for a specific Podmortem resource */
    private void processPodFailureForPodmortem(Podmortem podmortem, Pod pod) {
        log.info(
                "Processing pod failure for pod: {} with podmortem: {}",
                pod.getMetadata().getName(),
                podmortem.getMetadata().getName());

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
                                handleAnalysisResult(podmortem, pod, analysisResult);
                            },
                            failure -> {
                                log.error(
                                        "Log analysis failed for pod: {}",
                                        pod.getMetadata().getName(),
                                        failure);
                                updatePodFailureStatusAsync(
                                        podmortem, pod, "Analysis failed: " + failure.getMessage());
                            });

        } catch (Exception e) {
            log.error("Error processing pod failure for pod: {}", pod.getMetadata().getName(), e);
            updatePodFailureStatusAsync(podmortem, pod, "Processing failed: " + e.getMessage());
        }
    }

    /** Collect pod failure data including logs and events */
    private PodFailureData collectPodFailureData(Pod pod) {
        // Get pod logs
        String podLogs =
                client.pods()
                        .inNamespace(pod.getMetadata().getNamespace())
                        .withName(pod.getMetadata().getName())
                        .getLog();

        // Get events for the pod
        List<Event> events =
                client.v1()
                        .events()
                        .inNamespace(pod.getMetadata().getNamespace())
                        .withField("involvedObject.name", pod.getMetadata().getName())
                        .list()
                        .getItems();

        return new PodFailureData(pod, podLogs, events);
    }

    /** Handle analysis result with AI provider */
    private void handleAnalysisResult(Podmortem podmortem, Pod pod, AnalysisResult analysisResult) {
        log.info("Handling analysis result for pod: {}", pod.getMetadata().getName());

        // Check if AI analysis is enabled
        if (Boolean.TRUE.equals(podmortem.getSpec().getAiAnalysisEnabled())
                && podmortem.getSpec().getAiProviderRef() != null) {

            // Use reactive approach to avoid blocking the event loop
            getAIProviderAsync(podmortem)
                    .subscribe()
                    .with(
                            aiProvider -> {
                                if (aiProvider.isPresent()) {
                                    // Send to AI interface
                                    aiInterfaceClient
                                            .generateExplanation(analysisResult, aiProvider.get())
                                            .subscribe()
                                            .with(
                                                    aiResponse -> {
                                                        log.info(
                                                                "AI analysis completed for pod: {}",
                                                                pod.getMetadata().getName());
                                                        updatePodFailureStatusAsync(
                                                                podmortem,
                                                                pod,
                                                                "Analysis completed with AI: "
                                                                        + aiResponse
                                                                                .getExplanation());
                                                    },
                                                    failure -> {
                                                        log.error(
                                                                "AI analysis failed for pod: {}",
                                                                pod.getMetadata().getName(),
                                                                failure);
                                                        updatePodFailureStatusAsync(
                                                                podmortem,
                                                                pod,
                                                                "Pattern analysis completed, AI failed: "
                                                                        + failure.getMessage());
                                                    });
                                } else {
                                    updatePodFailureStatusAsync(
                                            podmortem,
                                            pod,
                                            "Analysis completed, AI provider not found");
                                }
                            },
                            failure -> {
                                log.error(
                                        "Failed to get AI provider for pod: {}",
                                        pod.getMetadata().getName(),
                                        failure);
                                updatePodFailureStatusAsync(
                                        podmortem,
                                        pod,
                                        "Analysis completed, AI provider lookup failed");
                            });
        } else {
            updatePodFailureStatusAsync(podmortem, pod, "Pattern analysis completed (AI disabled)");
        }
    }

    /** Update the Podmortem resource status asynchronously */
    private void updatePodFailureStatusAsync(Podmortem podmortem, Pod pod, String message) {
        // Use Uni.createFrom().item() to run on worker thread
        io.smallrye.mutiny.Uni.createFrom()
                .item(
                        () -> {
                            try {
                                if (podmortem.getStatus() == null) {
                                    podmortem.setStatus(new PodmortemStatus());
                                }

                                podmortem
                                        .getStatus()
                                        .setMessage(
                                                message
                                                        + " (Pod: "
                                                        + pod.getMetadata().getName()
                                                        + ")");
                                podmortem.getStatus().setPhase("Processing");

                                client.resource(podmortem).patchStatus();
                                log.info(
                                        "Updated status for pod {}: {}",
                                        pod.getMetadata().getName(),
                                        message);
                                return true;

                            } catch (Exception e) {
                                log.error(
                                        "Failed to update podmortem status for pod {}: {}",
                                        pod.getMetadata().getName(),
                                        e.getMessage(),
                                        e);
                                return false;
                            }
                        })
                .runSubscriptionOn(
                        io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .subscribe()
                .with(
                        result -> {
                            if (result) {
                                log.debug(
                                        "Status update completed for pod: {}",
                                        pod.getMetadata().getName());
                            }
                        },
                        failure ->
                                log.error(
                                        "Status update failed for pod: {}",
                                        pod.getMetadata().getName(),
                                        failure));
    }

    /** Get AI provider for the Podmortem resource asynchronously */
    private io.smallrye.mutiny.Uni<Optional<AIProvider>> getAIProviderAsync(Podmortem podmortem) {
        if (podmortem.getSpec().getAiProviderRef() == null) {
            return io.smallrye.mutiny.Uni.createFrom().item(Optional.empty());
        }

        // Run the blocking Kubernetes API call on a worker thread
        return io.smallrye.mutiny.Uni.createFrom()
                .item(
                        () -> {
                            try {
                                String providerName =
                                        podmortem.getSpec().getAiProviderRef().getName();
                                String providerNamespace =
                                        podmortem.getSpec().getAiProviderRef().getNamespace();

                                // Default to podmortem namespace if not specified
                                if (providerNamespace == null) {
                                    providerNamespace = podmortem.getMetadata().getNamespace();
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

    /** Restart the watcher after a failure */
    private void restartWatcher() {
        new Thread(
                        () -> {
                            try {
                                Thread.sleep(5000); // Wait 5 seconds before restart
                                log.info("Restarting pod failure watcher...");
                                startPodWatcher();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.error("Watcher restart interrupted", e);
                            }
                        })
                .start();
    }
}
