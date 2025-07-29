package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisRequest;
import com.redhat.podmortem.common.model.provider.AIResponse;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client interface for communicating with the AI Interface service.
 *
 * <p>Provides reactive endpoints for submitting pod failure analysis requests to the AI interface
 * service for explanation generation. Uses Quarkus REST Client with Mutiny for non-blocking
 * operations.
 */
@ApplicationScoped
@RegisterRestClient(configKey = "ai-interface")
@Path("/api/v1/analysis")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface AIInterfaceRestClient {

    /**
     * Requests AI explanation generation for a pod failure analysis.
     *
     * <p>Submits log analysis results and AI provider configuration to generate human-readable
     * explanations of the failure causes and potential solutions.
     *
     * @param request the analysis request containing log results and AI provider configuration
     * @return a Uni that emits the AI-generated explanation response
     */
    @POST
    @Path("/analyze")
    Uni<AIResponse> analyzeFailure(AnalysisRequest request);
}
