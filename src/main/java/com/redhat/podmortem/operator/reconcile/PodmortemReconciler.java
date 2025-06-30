package com.redhat.podmortem.operator.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.common.model.kube.podmortem.Podmortem;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ControllerConfiguration
public class PodmortemReconciler implements Reconciler<Podmortem> {

    private static final Logger log = LoggerFactory.getLogger(PodmortemReconciler.class);
    private final KubernetesClient client;
    private final ObjectMapper objectMapper;

    public PodmortemReconciler(KubernetesClient client) {
        this.client = client;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public UpdateControl<Podmortem> reconcile(Podmortem resource, Context<Podmortem> context) {
        log.info("Reconciling PodmortemConfig: {}", resource.getMetadata().getName());

        List<Pod> pods =
                client.pods()
                        .inAnyNamespace()
                        .withLabelSelector(resource.getSpec().getPodSelector())
                        .list()
                        .getItems();

        for (Pod pod : pods) {
            pod.getStatus()
                    .getContainerStatuses()
                    .forEach(
                            containerStatus -> {
                                if (containerStatus.getState().getTerminated() != null
                                        && containerStatus.getState().getTerminated().getExitCode()
                                                != 0) {

                                    log.info(
                                            "Pod has a container with an ungraceful exit",
                                            "pod",
                                            pod.getMetadata().getName(),
                                            "container",
                                            containerStatus.getName(),
                                            "exitCode",
                                            containerStatus
                                                    .getState()
                                                    .getTerminated()
                                                    .getExitCode());

                                    // get Logs
                                    String podLogs =
                                            client.pods()
                                                    .inNamespace(pod.getMetadata().getNamespace())
                                                    .withName(pod.getMetadata().getName())
                                                    .getLog();

                                    // get Events for the Pod
                                    List<Event> events =
                                            client.events()
                                                    .v1()
                                                    .events()
                                                    .inNamespace(pod.getMetadata().getNamespace())
                                                    .withField(
                                                            "involvedObject.name",
                                                            pod.getMetadata().getName())
                                                    .list()
                                                    .getItems();

                                    // assemble the failure data
                                    PodFailureData failureData =
                                            new PodFailureData(pod, podLogs, events);

                                    // marshal data to JSON and log it
                                    try {
                                        String jsonData =
                                                objectMapper.writeValueAsString(failureData);
                                        log.info("Collected pod failure data:\n{}", jsonData);
                                    } catch (JsonProcessingException e) {
                                        log.error(
                                                "Failed to marshal failure data to JSON for pod {}",
                                                pod.getMetadata().getName(),
                                                e);
                                    }
                                }
                            });
        }
        return UpdateControl.noUpdate();
    }
}
