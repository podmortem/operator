package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.kube.patternlibrary.PatternLibrary;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import com.redhat.podmortem.common.model.kube.podmortem.PodmortemStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-time pod failure monitoring service using Kubernetes watch APIs.
 *
 * <p>Continuously monitors all pods across all namespaces for failure events, automatically
 * triggering failure analysis workflows when pods terminate with non-zero exit codes. Provides
 * duplicate detection and automatic recovery from watch connection failures.
 */
@ApplicationScoped
public class PodFailureWatcher {

    private static final Logger log = LoggerFactory.getLogger(PodFailureWatcher.class);

    @Inject KubernetesClient client;
    @Inject LogParserClient logParserClient;
    @Inject AIInterfaceClient aiInterfaceClient;
    @Inject EventService eventService;
    @Inject AnalysisStorageService analysisStorageService;

    // track processed failures
    private final Map<String, Instant> processedFailures = new ConcurrentHashMap<>();

    @ConfigProperty(name = "podmortem.watch.namespaces")
    Optional<String> watchNamespacesProperty;

    private Set<String> allowedNamespaces = Set.of();
    private final List<Watch> activeWatches = new CopyOnWriteArrayList<>();

    @ConfigProperty(name = "podmortem.processing.startup-delay-seconds", defaultValue = "60")
    int startupDelaySeconds;

    @ConfigProperty(name = "quarkus.rest-client.log-parser.url")
    Optional<String> logParserBaseUrlProperty;

    @ConfigProperty(name = "quarkus.rest-client.ai-interface.url")
    Optional<String> aiInterfaceBaseUrlProperty;

    private final AtomicBoolean systemReady = new AtomicBoolean(false);
    private Instant appStartupTime = Instant.now();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    // Queue pod failures until system is ready
    private static final int MAX_PENDING_QUEUE_SIZE = 500;
    private final ConcurrentLinkedQueue<String> pendingFailureQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> queuedFailureKeys =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Initializes the pod failure watcher on application startup.
     *
     * <p>Automatically starts monitoring pod events when the application starts.
     *
     * @param event the Quarkus startup event
     */
    public void onStartup(@Observes StartupEvent event) {
        log.info("Starting real-time pod failure watcher");
        appStartupTime = Instant.now();
        // Parse configured namespaces (comma-separated)
        String namespaces = watchNamespacesProperty.orElse("");
        if (!namespaces.isBlank()) {
            allowedNamespaces =
                    Arrays.stream(namespaces.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(java.util.stream.Collectors.toSet());
            log.info("Configured to watch namespaces: {}", allowedNamespaces);
        } else {
            allowedNamespaces = Set.of();
            log.info("Configured to watch all namespaces (no namespace filter set)");
        }
        startPodWatcher();
        startReadinessGuard();
    }

    /**
     * Starts the Kubernetes pod watcher for real-time failure detection.
     *
     * <p>Establishes a watch on all pods across all namespaces, monitoring for state changes that
     * indicate failures. Includes automatic recovery logic for watch connection failures.
     */
    private void startPodWatcher() {
        List<String> targetNamespaces = new ArrayList<>(allowedNamespaces);
        if (targetNamespaces.isEmpty()) {
            Watch watch = client.pods().inAnyNamespace().watch(createWatcher());
            activeWatches.add(watch);
        } else {
            for (String ns : targetNamespaces) {
                Watch watch = client.pods().inNamespace(ns).watch(createWatcher());
                activeWatches.add(watch);
            }
        }
    }

    private Watcher<Pod> createWatcher() {
        return new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod pod) {
                try {
                    if (action != Action.MODIFIED) {
                        return;
                    }
                    if (!allowedNamespaces.isEmpty()
                            && !allowedNamespaces.contains(pod.getMetadata().getNamespace())) {
                        return;
                    }
                    if (hasPodFailed(pod)) {
                        if (!systemReady.get()) {
                            enqueuePendingFailure(pod);
                            return;
                        }
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
                    log.error("Pod watcher closed due to error: {}", cause.getMessage(), cause);
                    // restart the watcher after a delay
                    restartWatcher();
                } else {
                    log.info("Pod watcher closed normally");
                }
            }
        };
    }

    /**
     * Starts a background readiness guard.
     *
     * <p>This guard periodically checks whether the system is allowed to process pod failures. The
     * system becomes ready when all of the following are true:
     *
     * <ul>
     *   <li>The configured startup delay (property: {@code
     *       podmortem.processing.startup-delay-seconds}) has elapsed
     *   <li>At least one {@code PatternLibrary} reports phase {@code Ready} (or none exist)
     *   <li>The Log Parser service {@code /q/health/ready} endpoint returns HTTP 2xx
     *   <li>The AI Interface {@code /q/health/ready} endpoint returns HTTP 2xx
     * </ul>
     *
     * <p>Once ready, any queued pod failure events are processed asynchronously.
     */
    private void startReadinessGuard() {
        Thread.ofVirtual()
                .name("podmortem-readiness-guard")
                .start(
                        () -> {
                            while (!systemReady.get()) {
                                try {
                                    boolean ready = checkSystemReady();
                                    if (ready) {
                                        systemReady.set(true);
                                        log.info(
                                                "System dependencies ready; enabling failure processing");
                                        processQueuedFailuresAsync();
                                        break;
                                    }
                                    Thread.sleep(5000);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (Exception e) {
                                    log.debug("Readiness check failed: {}", e.getMessage());
                                    try {
                                        Thread.sleep(5000);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            }
                        });
    }

    /**
     * Enqueues a failed pod event while the system is not yet ready to process failures.
     *
     * <p>Uses a bounded FIFO queue to avoid unbounded memory growth. Duplicate pod keys are
     * de-duplicated while pending. When the queue is full, the oldest entry is dropped and a warn
     * is logged.
     *
     * @param pod the pod associated with the failure event to buffer
     */
    private void enqueuePendingFailure(Pod pod) {
        String podKey = pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName();
        // Avoid duplicate entries
        if (queuedFailureKeys.add(podKey)) {
            if (pendingFailureQueue.size() >= MAX_PENDING_QUEUE_SIZE) {
                String dropped = pendingFailureQueue.poll();
                if (dropped != null) {
                    queuedFailureKeys.remove(dropped);
                    log.warn("Pending failure queue full; dropping oldest: {}", dropped);
                }
            }
            pendingFailureQueue.offer(podKey);
            log.debug("Queued pod failure event until ready: {}", podKey);
        }
    }

    /**
     * Drains the pending failure queue and processes each entry asynchronously.
     *
     * <p>For each queued item, the latest pod object is fetched to confirm the failure state before
     * processing; this avoids acting on stale data.
     */
    private void processQueuedFailuresAsync() {
        Thread.ofVirtual()
                .name("podmortem-queued-failure-drain")
                .start(
                        () -> {
                            String podKey;
                            while ((podKey = pendingFailureQueue.poll()) != null) {
                                try {
                                    queuedFailureKeys.remove(podKey);
                                    String[] parts = podKey.split("/", 2);
                                    if (parts.length != 2) {
                                        continue;
                                    }
                                    String ns = parts[0];
                                    String name = parts[1];
                                    Pod latest = client.pods().inNamespace(ns).withName(name).get();
                                    if (latest != null && hasPodFailed(latest)) {
                                        handlePodFailure(latest);
                                    }
                                } catch (Exception e) {
                                    log.debug(
                                            "Failed processing queued failure {}: {}",
                                            podKey,
                                            e.getMessage());
                                }
                            }
                        });
    }

    /**
     * Evaluates whether the operator may begin processing pod failures.
     *
     * <p>Readiness requires that the startup delay has elapsed, pattern libraries are ready (or not
     * present), and dependent services (log-parser and AI interface) report ready via their {@code
     * /q/health/ready} endpoints.
     *
     * @return {@code true} if failure processing can start; {@code false} otherwise
     */
    private boolean checkSystemReady() {
        if (Duration.between(appStartupTime, Instant.now()).getSeconds() < startupDelaySeconds) {
            return false;
        }

        // Pattern libraries: ready if none defined or any reports phase Ready
        try {
            List<com.redhat.podmortem.common.model.kube.patternlibrary.PatternLibrary> libs =
                    client.resources(PatternLibrary.class).inAnyNamespace().list().getItems();
            boolean patternsReady =
                    libs.isEmpty()
                            || libs.stream()
                                    .anyMatch(
                                            l ->
                                                    l.getStatus() != null
                                                            && "Ready"
                                                                    .equalsIgnoreCase(
                                                                            l.getStatus()
                                                                                    .getPhase()));
            if (!patternsReady) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        // log-parser ready
        String logParserUrl =
                logParserBaseUrlProperty.orElseGet(
                        () -> System.getenv("QUARKUS_REST_CLIENT_LOG_PARSER_URL"));
        if (logParserUrl == null || logParserUrl.isBlank()) {
            return false;
        }
        if (!isServiceReady(logParserUrl)) {
            return false;
        }

        // ai-interface ready
        String aiUrl =
                aiInterfaceBaseUrlProperty.orElseGet(
                        () -> System.getenv("QUARKUS_REST_CLIENT_AI_INTERFACE_URL"));
        if (aiUrl == null || aiUrl.isBlank()) {
            return false;
        }
        if (!isServiceReady(aiUrl)) {
            return false;
        }

        return true;
    }

    /**
     * Checks whether a dependent service is ready by querying its readiness endpoint.
     *
     * @param baseUrl the base URL of the service (without trailing path); {@code /q/health/ready}
     *     will be appended
     * @return {@code true} if the service responds with HTTP 2xx, {@code false} otherwise
     */
    private boolean isServiceReady(String baseUrl) {
        try {
            String healthUrl =
                    baseUrl.endsWith("/")
                            ? baseUrl + "q/health/ready"
                            : baseUrl + "/q/health/ready";
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(healthUrl))
                            .timeout(Duration.ofSeconds(2))
                            .build();
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Determines if a pod has failed by examining container statuses.
     *
     * <p>A pod is considered failed if any container has terminated with a non-zero exit code.
     *
     * @param pod the pod to check for failure
     * @return true if the pod has failed, false otherwise
     */
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

    /**
     * Handles a pod failure event with duplicate detection and processing orchestration.
     *
     * <p>Tracks processed failures to avoid duplicate analysis, finds matching Podmortem resources,
     * and initiates failure processing workflows.
     *
     * @param pod the failed pod to process
     */
    private void handlePodFailure(Pod pod) {
        String podKey = pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName();

        // find matching Podmortem resources first; if none, ignore silently
        List<Podmortem> podmortemResources = findMatchingPodmortemResources(pod);
        if (podmortemResources.isEmpty()) {
            log.debug("Ignoring failure for unmonitored pod: {}", podKey);
            return;
        }

        // check if we've already processed this failure
        Instant failureTime = getFailureTime(pod);
        if (failureTime != null && processedFailures.containsKey(podKey)) {
            Instant previousFailure = processedFailures.get(podKey);
            if (failureTime.equals(previousFailure)) {
                log.debug("Already processed failure for pod: {}", podKey);
                return;
            }
        }

        log.info("Pod failure detected: {} ({} monitors)", podKey, podmortemResources.size());

        // mark as processed
        if (failureTime != null) {
            processedFailures.put(podKey, failureTime);
        }

        for (Podmortem podmortem : podmortemResources) {
            eventService.emitFailureDetected(pod, podmortem);
            processPodFailureForPodmortem(podmortem, pod);
        }
    }

    /**
     * Extracts the failure timestamp from a pod's container status.
     *
     * @param pod the pod to extract failure time from
     * @return the timestamp when the pod failed, or null if not available
     */
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

    /**
     * Finds all Podmortem resources that have selectors matching the failed pod.
     *
     * @param pod the failed pod to find matches for
     * @return a list of Podmortem resources with matching selectors
     */
    private List<Podmortem> findMatchingPodmortemResources(Pod pod) {
        List<Podmortem> allPodmortem =
                client.resources(Podmortem.class).inAnyNamespace().list().getItems();

        return allPodmortem.stream()
                .filter(podmortem -> podMatchesSelector(pod, podmortem))
                .toList();
    }

    /**
     * Determines if a pod matches a Podmortem resource's label selector.
     *
     * <p>Compares the pod's labels against the Podmortem's selector to determine if this failure
     * should be processed by that Podmortem resource.
     *
     * @param pod the pod to check for matching
     * @param podmortem the Podmortem resource with selector criteria
     * @return true if the pod matches the selector, false otherwise
     */
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

        // check if all selector labels match pod labels
        return selector.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(podLabels.get(entry.getKey())));
    }

    /**
     * Processes a pod failure for a specific Podmortem resource.
     *
     * <p>Collects comprehensive failure data and initiates the analysis workflow including pattern
     * analysis and optional AI explanation generation.
     *
     * @param podmortem the Podmortem resource managing this failure
     * @param pod the failed pod to process
     */
    private void processPodFailureForPodmortem(Podmortem podmortem, Pod pod) {
        log.info(
                "Processing pod failure for pod: {} with podmortem: {}",
                pod.getMetadata().getName(),
                podmortem.getMetadata().getName());

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
                                handleAnalysisResult(podmortem, pod, analysisResult);
                            },
                            failure -> {
                                log.error(
                                        "Log analysis failed for pod: {}",
                                        pod.getMetadata().getName(),
                                        failure);
                                updatePodFailureStatusAsync(
                                        podmortem, pod, "Analysis failed: " + failure.getMessage());
                                eventService.emitAnalysisError(
                                        pod, podmortem, "Analysis failed: " + failure.getMessage());
                            });

        } catch (Exception e) {
            log.error("Error processing pod failure for pod: {}", pod.getMetadata().getName(), e);
            updatePodFailureStatusAsync(podmortem, pod, "Processing failed: " + e.getMessage());
            eventService.emitAnalysisError(pod, podmortem, "Processing failed: " + e.getMessage());
        }
    }

    /**
     * Collects comprehensive failure data including logs and events for analysis.
     *
     * @param pod the failed pod to collect data from
     * @return a PodFailureData object containing all diagnostic information
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
     * <p>Based on the Podmortem configuration, either completes with pattern analysis only or
     * forwards results to the AI interface for explanation generation.
     *
     * @param podmortem the Podmortem resource managing this analysis
     * @param pod the pod that was analyzed
     * @param analysisResult the pattern analysis results from log parsing
     */
    private void handleAnalysisResult(Podmortem podmortem, Pod pod, AnalysisResult analysisResult) {
        log.debug("Handling analysis result for pod: {}", pod.getMetadata().getName());

        if (Boolean.TRUE.equals(podmortem.getSpec().getAiAnalysisEnabled())
                && podmortem.getSpec().getAiProviderRef() != null) {

            getAIProviderAsync(podmortem)
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
                                                        String aiAnalysis =
                                                                aiResponse.getExplanation();

                                                        analysisStorageService.storeAnalysisResults(
                                                                pod,
                                                                podmortem,
                                                                analysisResult,
                                                                aiAnalysis);

                                                        updatePodFailureStatusAsync(
                                                                podmortem,
                                                                pod,
                                                                "Analysis completed with AI analysis");
                                                        eventService.emitAnalysisComplete(
                                                                pod,
                                                                podmortem,
                                                                analysisResult,
                                                                aiAnalysis);
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
                                                        eventService.emitAnalysisComplete(
                                                                pod,
                                                                podmortem,
                                                                analysisResult,
                                                                "AI failed: "
                                                                        + failure.getMessage());
                                                        eventService.emitAnalysisError(
                                                                pod,
                                                                podmortem,
                                                                "AI analysis failed: "
                                                                        + failure.getMessage());
                                                    });
                                } else {
                                    analysisStorageService.storeAnalysisResults(
                                            pod, podmortem, analysisResult, null);

                                    updatePodFailureStatusAsync(
                                            podmortem,
                                            pod,
                                            "Analysis completed, AI provider not found");
                                    eventService.emitAnalysisComplete(
                                            pod,
                                            podmortem,
                                            analysisResult,
                                            "AI provider not found");
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
                                eventService.emitAnalysisComplete(
                                        pod,
                                        podmortem,
                                        analysisResult,
                                        "AI provider lookup failed");
                            });
        } else {
            analysisStorageService.storeAnalysisResults(pod, podmortem, analysisResult, null);

            updatePodFailureStatusAsync(podmortem, pod, "Pattern analysis completed (AI disabled)");
            eventService.emitAnalysisComplete(pod, podmortem, analysisResult, "AI disabled");
        }
    }

    /**
     * Asynchronously updates the Podmortem resource status for a specific pod failure.
     *
     * @param podmortem the Podmortem resource to update
     * @param pod the pod that was processed
     * @param message the status message for this failure
     */
    private void updatePodFailureStatusAsync(Podmortem podmortem, Pod pod, String message) {
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
                                log.debug(
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

    /**
     * Asynchronously retrieves the AI provider referenced by a Podmortem resource.
     *
     * @param podmortem the Podmortem resource containing the AI provider reference
     * @return a Uni that emits an Optional containing the AI provider if found
     */
    private io.smallrye.mutiny.Uni<Optional<AIProvider>> getAIProviderAsync(Podmortem podmortem) {
        if (podmortem.getSpec().getAiProviderRef() == null) {
            return io.smallrye.mutiny.Uni.createFrom().item(Optional.empty());
        }

        return io.smallrye.mutiny.Uni.createFrom()
                .item(
                        () -> {
                            try {
                                String providerName =
                                        podmortem.getSpec().getAiProviderRef().getName();
                                String providerNamespace =
                                        podmortem.getSpec().getAiProviderRef().getNamespace();

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

    /** Restarts the pod watcher after a connection failure. */
    private void restartWatcher() {
        for (Watch w : activeWatches) {
            try {
                w.close();
            } catch (Exception ignored) {
            }
        }
        activeWatches.clear();

        Thread.ofVirtual()
                .name("podmortem-watcher-restart")
                .start(
                        () -> {
                            try {
                                Thread.sleep(5000);
                                log.info("Restarting pod failure watcher...");
                                startPodWatcher();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.error("Watcher restart interrupted", e);
                            }
                        });
    }
}
