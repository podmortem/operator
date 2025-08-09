package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import com.redhat.podmortem.common.model.kube.podmortem.PodmortemStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for storing and retrieving full analysis results.
 *
 * <p>This service provides multiple storage mechanisms for analysis results:
 *
 * <ul>
 *   <li>Pod annotations for immediate access (full AI analysis)
 *   <li>Podmortem CR status for historical tracking
 * </ul>
 *
 * <p>Users can retrieve the full analysis by:
 *
 * <ul>
 *   <li>Checking pod annotations: {@code kubectl describe pod <pod-name> | grep podmortem}
 *   <li>Viewing Podmortem status: {@code kubectl get podmortem <name> -o yaml}
 * </ul>
 */
@ApplicationScoped
public class AnalysisStorageService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisStorageService.class);

    private static final String ANNOTATION_PREFIX = "podmortem.io/";
    private static final String ANALYSIS_ANNOTATION = ANNOTATION_PREFIX + "analysis";
    private static final String SEVERITY_ANNOTATION = ANNOTATION_PREFIX + "severity";
    private static final String TIMESTAMP_ANNOTATION = ANNOTATION_PREFIX + "analyzed-at";
    private static final String MONITOR_ANNOTATION = ANNOTATION_PREFIX + "monitor";

    private static final int MAX_RECENT_FAILURES = 10;

    @Inject KubernetesClient client;

    /**
     * Stores the full analysis results in multiple locations for user access.
     *
     * @param pod the analyzed pod
     * @param monitor the Podmortem resource that managed the analysis
     * @param result the pattern analysis result
     * @param aiAnalysis the full AI analysis text (may be null if AI is disabled)
     */
    public void storeAnalysisResults(
            Pod pod, Podmortem monitor, AnalysisResult result, String aiAnalysis) {

        storeToPodAnnotations(pod, monitor, result, aiAnalysis);
        storeToPodmortemStatus(pod, monitor, result, aiAnalysis);
    }

    /**
     * Stores analysis results as pod annotations for immediate access. This allows users to see the
     * full analysis with: kubectl describe pod
     */
    private void storeToPodAnnotations(
            Pod pod, Podmortem monitor, AnalysisResult result, String aiAnalysis) {

        Uni.createFrom()
                .item(
                        () -> {
                            try {
                                Map<String, String> annotations =
                                        pod.getMetadata().getAnnotations();
                                if (annotations == null) {
                                    annotations = new HashMap<>();
                                }

                                if (aiAnalysis != null && !aiAnalysis.isBlank()) {
                                    annotations.put(ANALYSIS_ANNOTATION, aiAnalysis);
                                } else {
                                    String summary =
                                            String.format(
                                                    "Pattern Analysis: Severity=%s, SignificantEvents=%d, TotalMatches=%d",
                                                    result.getSummary() != null
                                                            ? result.getSummary()
                                                                    .getHighestSeverity()
                                                            : "UNKNOWN",
                                                    result.getSummary() != null
                                                            ? result.getSummary()
                                                                    .getSignificantEvents()
                                                            : 0,
                                                    result.getEvents() != null
                                                            ? result.getEvents().size()
                                                            : 0);
                                    annotations.put(ANALYSIS_ANNOTATION, summary);
                                }

                                // Store metadata
                                if (result.getSummary() != null
                                        && result.getSummary().getHighestSeverity() != null) {
                                    annotations.put(
                                            SEVERITY_ANNOTATION,
                                            result.getSummary().getHighestSeverity());
                                }
                                annotations.put(TIMESTAMP_ANNOTATION, Instant.now().toString());
                                annotations.put(
                                        MONITOR_ANNOTATION, monitor.getMetadata().getName());

                                pod.getMetadata().setAnnotations(annotations);
                                client.pods()
                                        .inNamespace(pod.getMetadata().getNamespace())
                                        .withName(pod.getMetadata().getName())
                                        .patch(pod);

                                log.debug(
                                        "Stored analysis results in pod annotations: {}",
                                        pod.getMetadata().getName());
                                return true;

                            } catch (Exception e) {
                                log.warn(
                                        "Failed to store analysis in pod annotations: {}",
                                        e.getMessage());
                                return false;
                            }
                        })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribe()
                .with(
                        success -> {
                            if (success) {
                                log.trace("Pod annotations updated successfully");
                            }
                        },
                        failure ->
                                log.debug(
                                        "Failed to update pod annotations: {}",
                                        failure.getMessage()));
    }

    /**
     * Stores analysis results in the Podmortem CR status for historical tracking. This maintains a
     * list of recent failures with their full analysis.
     */
    private void storeToPodmortemStatus(
            Pod pod, Podmortem monitor, AnalysisResult result, String aiAnalysis) {

        Uni.createFrom()
                .item(
                        () -> {
                            try {
                                Podmortem latest =
                                        client.resources(Podmortem.class)
                                                .inNamespace(monitor.getMetadata().getNamespace())
                                                .withName(monitor.getMetadata().getName())
                                                .get();

                                if (latest == null) {
                                    log.warn(
                                            "Podmortem not found: {}",
                                            monitor.getMetadata().getName());
                                    return false;
                                }

                                if (latest.getStatus() == null) {
                                    latest.setStatus(new PodmortemStatus());
                                }

                                List<PodmortemStatus.PodFailureStatus> recentFailures =
                                        latest.getStatus().getRecentFailures();
                                if (recentFailures == null) {
                                    recentFailures = new ArrayList<>();
                                }

                                PodmortemStatus.PodFailureStatus failureStatus =
                                        new PodmortemStatus.PodFailureStatus();
                                failureStatus.setPodName(pod.getMetadata().getName());
                                failureStatus.setPodNamespace(pod.getMetadata().getNamespace());
                                failureStatus.setFailureTime(Instant.now());
                                failureStatus.setAnalysisStatus("Completed");

                                if (aiAnalysis != null && !aiAnalysis.isBlank()) {
                                    failureStatus.setExplanation(aiAnalysis);
                                } else {
                                    StringBuilder explanation = new StringBuilder();
                                    explanation.append("Pattern Analysis Results:\n");
                                    explanation.append("========================\n");
                                    if (result.getSummary() != null) {
                                        explanation
                                                .append("Highest Severity: ")
                                                .append(result.getSummary().getHighestSeverity())
                                                .append("\n");
                                        explanation
                                                .append("Significant Events: ")
                                                .append(result.getSummary().getSignificantEvents())
                                                .append("\n");
                                    }
                                    if (result.getEvents() != null
                                            && !result.getEvents().isEmpty()) {
                                        explanation.append("\nTop Matches:\n");
                                        result.getEvents().stream()
                                                .limit(5)
                                                .forEach(
                                                        event -> {
                                                            if (event.getMatchedPattern() != null) {
                                                                explanation
                                                                        .append("- ")
                                                                        .append(
                                                                                event.getMatchedPattern()
                                                                                        .getName())
                                                                        .append(" (Severity: ")
                                                                        .append(
                                                                                event.getMatchedPattern()
                                                                                        .getSeverity())
                                                                        .append(", Score: ")
                                                                        .append(
                                                                                String.format(
                                                                                        "%.2f",
                                                                                        event
                                                                                                .getScore()))
                                                                        .append(")\n");
                                                            }
                                                        });
                                    }
                                    failureStatus.setExplanation(explanation.toString());
                                }

                                recentFailures.add(0, failureStatus);

                                if (recentFailures.size() > MAX_RECENT_FAILURES) {
                                    recentFailures = recentFailures.subList(0, MAX_RECENT_FAILURES);
                                }

                                latest.getStatus().setRecentFailures(recentFailures);
                                latest.getStatus().setLastUpdate(Instant.now());

                                client.resource(latest).patchStatus();

                                log.debug(
                                        "Stored analysis results in Podmortem status: {}",
                                        latest.getMetadata().getName());
                                return true;

                            } catch (Exception e) {
                                log.warn(
                                        "Failed to store analysis in Podmortem status: {}",
                                        e.getMessage());
                                return false;
                            }
                        })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribe()
                .with(
                        success -> {
                            if (Boolean.TRUE.equals(success)) {
                                log.trace("Podmortem status updated successfully");
                            }
                        },
                        failure ->
                                log.debug(
                                        "Failed to update Podmortem status: {}",
                                        failure.getMessage()));
    }
}
