package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client interface for communicating with the Log Parser service.
 *
 * <p>Provides reactive endpoints for submitting pod failure data to the log parser for pattern
 * analysis and failure detection. Uses Quarkus REST Client with Mutiny for non-blocking log
 * analysis operations.
 */
@ApplicationScoped
@RegisterRestClient(configKey = "log-parser")
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface LogParserRestClient {

    /**
     * Submits pod failure data for log pattern analysis.
     *
     * <p>Processes comprehensive pod failure information including logs, events, and metadata to
     * identify failure patterns and calculate confidence scores.
     *
     * @param failureData the complete pod failure data containing logs and diagnostic information
     * @return a Uni that emits the analysis results with matched patterns and scores
     */
    @POST
    @Path("/parse")
    Uni<AnalysisResult> parseLogs(PodFailureData failureData);
}
