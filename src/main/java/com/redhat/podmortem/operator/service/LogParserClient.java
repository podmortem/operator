package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class LogParserClient {

    private static final Logger log = LoggerFactory.getLogger(LogParserClient.class);

    @Inject @RestClient LogParserRestClient restClient;

    public Uni<AnalysisResult> analyzeLog(PodFailureData failureData) {
        log.debug(
                "Sending log analysis request for pod: {}",
                failureData.getPod().getMetadata().getName());
        return restClient
                .parseLogs(failureData)
                .onItem()
                .invoke(
                        result ->
                                log.debug(
                                        "Received analysis result for pod: {}",
                                        failureData.getPod().getMetadata().getName()))
                .onFailure()
                .invoke(
                        throwable ->
                                log.error(
                                        "Log analysis failed for pod: {}",
                                        failureData.getPod().getMetadata().getName(),
                                        throwable));
    }
}
