package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client service for communicating with the Log Parser REST API.
 *
 * <p>Provides reactive methods for submitting pod failure data to the log parser service for
 * pattern analysis and failure detection. Handles error logging and provides debugging information
 * for analysis requests.
 */
@ApplicationScoped
public class LogParserClient {

    private static final Logger log = LoggerFactory.getLogger(LogParserClient.class);

    @Inject @RestClient LogParserRestClient restClient;

    /**
     * Submits pod failure data to the log parser for analysis.
     *
     * <p>Sends comprehensive pod failure information including logs, events, and metadata to the
     * log parser service for pattern matching and failure analysis.
     *
     * @param failureData the complete pod failure data including logs and events
     * @return a Uni that emits the analysis results containing matched patterns and confidence
     *     scores
     */
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
