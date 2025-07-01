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

@ApplicationScoped
@RegisterRestClient(configKey = "log-parser")
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface LogParserRestClient {

    @POST
    @Path("/parse")
    Uni<AnalysisResult> parseLogs(PodFailureData failureData);
}
