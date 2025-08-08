package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.MicroTimeBuilder;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.api.model.events.v1.EventBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Service to emit Kubernetes Events (events.k8s.io/v1) for analysis lifecycle and results. */
@ApplicationScoped
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private static final String REPORTING_CONTROLLER = "podmortem.operator";

    @Inject KubernetesClient client;

    /**
     * Emits a failure-detected event when a pod failure is observed and queued for analysis.
     *
     * <p>Emits to the failed pod, its owning deployment (if present), and the Podmortem monitor
     * resource.
     *
     * @param pod the failed pod that triggered analysis
     * @param monitor the Podmortem resource that will process the failure
     */
    public void emitFailureDetected(Pod pod, Podmortem monitor) {
        String reason = "PodFailureDetected";
        String message = "Pod failure detected and queued for analysis";
        emitForTargets(pod, monitor, reason, message, "Warning");
        findOwningDeployment(pod)
                .ifPresent(
                        deployment ->
                                emit(
                                        deployment,
                                        pod.getMetadata().getNamespace(),
                                        reason,
                                        message,
                                        "Warning"));
    }

    /**
     * Emits an analysis-complete event with a concise summary of results.
     *
     * <p>Includes the highest severity and the number of significant events identified. Emits to
     * the failed pod, its owning deployment (if present), and the Podmortem resource. When {@code
     * detail} is provided, it is appended to the message (for example, AI usage or errors).
     *
     * @param pod the analyzed pod
     * @param monitor the Podmortem resource that managed the analysis
     * @param result the pattern analysis result used to construct the summary
     * @param detail optional extra details appended to the message (e.g., AI analysis text or
     *     failure note)
     */
    public void emitAnalysisComplete(
            Pod pod, Podmortem monitor, AnalysisResult result, String detail) {
        String highestSeverity =
                result.getSummary() != null ? result.getSummary().getHighestSeverity() : null;
        int significant =
                result.getSummary() != null ? result.getSummary().getSignificantEvents() : 0;
        String message =
                String.format(
                                "Analysis complete. HighestSeverity=%s, SignificantEvents=%d",
                                highestSeverity, significant)
                        + (detail != null && !detail.isBlank()
                                ? " | " + truncate(detail, 900)
                                : "");

        String reason = "PodmortemAnalysisComplete";
        emitForTargets(pod, monitor, reason, message, "Normal");
        findOwningDeployment(pod)
                .ifPresent(
                        deployment ->
                                emit(
                                        deployment,
                                        pod.getMetadata().getNamespace(),
                                        reason,
                                        message,
                                        "Normal"));
    }

    /**
     * Emits an analysis error event when an error occurs during analysis or AI processing.
     *
     * <p>Emits to the failed pod, its owning deployment (if present), and the Podmortem resource.
     *
     * @param pod the pod related to the failed analysis
     * @param monitor the Podmortem resource managing the analysis workflow
     * @param errorMessage a concise, user-readable error description
     */
    public void emitAnalysisError(Pod pod, Podmortem monitor, String errorMessage) {
        String reason = "PodmortemAnalysisError";
        String message = truncate(errorMessage, 900);
        emitForTargets(pod, monitor, reason, message, "Warning");
        findOwningDeployment(pod)
                .ifPresent(
                        deployment ->
                                emit(
                                        deployment,
                                        pod.getMetadata().getNamespace(),
                                        reason,
                                        message,
                                        "Warning"));
    }

    /**
     * Emits a single event to both primary targets: the pod and the Podmortem resource.
     *
     * @param pod the pod target
     * @param monitor the Podmortem CR target
     * @param reason the machine-friendly reason string
     * @param message the human-readable message
     * @param type the event type (for example, "Normal" or "Warning")
     */
    private void emitForTargets(
            Pod pod, Podmortem monitor, String reason, String message, String type) {
        // Pod
        emit(pod, pod.getMetadata().getNamespace(), reason, message, type);
        // Monitor CR
        emit(monitor, monitor.getMetadata().getNamespace(), reason, message, type);
    }

    /**
     * Emits an event for a specific Kubernetes resource.
     *
     * @param target the resource the event should be associated with
     * @param namespace the namespace to create the event in
     * @param reason a short, machine-friendly string that categorizes the event
     * @param message a human-readable description of the event
     * @param type the event type (for example, "Normal" or "Warning")
     */
    private void emit(
            HasMetadata target, String namespace, String reason, String message, String type) {
        try {
            ObjectReference ref = new ObjectReference();
            ref.setApiVersion(target.getApiVersion());
            ref.setKind(target.getKind());
            ref.setName(target.getMetadata().getName());
            ref.setNamespace(target.getMetadata().getNamespace());
            ref.setUid(target.getMetadata().getUid());

            Event event =
                    new EventBuilder()
                            .withNewMetadata()
                            .withName(generateEventName(target))
                            .withNamespace(namespace)
                            .endMetadata()
                            .withReason(reason)
                            .withType(type)
                            .withAction("Report")
                            .withNote(message)
                            .withReportingController(REPORTING_CONTROLLER)
                            .withReportingInstance(REPORTING_CONTROLLER + ".instance")
                            .withEventTime(
                                    new MicroTimeBuilder()
                                            .withTime(Instant.now().toString())
                                            .build())
                            .withRegarding(ref)
                            .build();

            client.events().v1().events().inNamespace(namespace).resource(event).create();
        } catch (Exception e) {
            log.debug("Failed to emit event '{}': {}", reason, e.getMessage());
        }
    }

    /**
     * Resolves the owning deployment for the given pod by traversing ReplicaSet ownership.
     *
     * @param pod the pod whose owning deployment should be resolved
     * @return an {@link Optional} containing the owning deployment if present
     */
    private Optional<Deployment> findOwningDeployment(Pod pod) {
        List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
        if (owners == null) return Optional.empty();

        // If owned by ReplicaSet, traverse up to Deployment
        return owners.stream()
                .filter(or -> "ReplicaSet".equals(or.getKind()))
                .findFirst()
                .flatMap(
                        rsOwner -> {
                            ReplicaSet rs =
                                    client.apps()
                                            .replicaSets()
                                            .inNamespace(pod.getMetadata().getNamespace())
                                            .withName(rsOwner.getName())
                                            .get();
                            if (rs == null || rs.getMetadata() == null) return Optional.empty();
                            List<OwnerReference> rsOwners = rs.getMetadata().getOwnerReferences();
                            if (rsOwners == null) return Optional.empty();
                            return rsOwners.stream()
                                    .filter(or -> "Deployment".equals(or.getKind()))
                                    .findFirst()
                                    .map(
                                            depOwner ->
                                                    client.apps()
                                                            .deployments()
                                                            .inNamespace(
                                                                    pod.getMetadata()
                                                                            .getNamespace())
                                                            .withName(depOwner.getName())
                                                            .get());
                        });
    }

    /**
     * Generates a unique, time-suffixed event name for a target resource.
     *
     * @param target the resource the event pertains to
     * @return a unique event name
     */
    private String generateEventName(HasMetadata target) {
        String base = target.getMetadata().getName();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return base + "." + suffix + "." + Instant.now().toEpochMilli();
    }

    /**
     * Truncates text to a maximum length, appending an ellipsis when truncated.
     *
     * @param text the input text (may be null)
     * @param max the maximum length to retain
     * @return the truncated text, or the original if within the limit
     */
    private String truncate(String text, int max) {
        if (text == null) return null;
        if (text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }
}
