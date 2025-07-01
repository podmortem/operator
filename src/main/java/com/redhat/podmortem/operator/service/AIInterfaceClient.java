package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.provider.AIResponse;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AIInterfaceClient {

    private static final Logger log = LoggerFactory.getLogger(AIInterfaceClient.class);

    @Inject @RestClient AIInterfaceRestClient restClient;

    public Uni<AIResponse> generateExplanation(
            AnalysisResult analysisResult, AIProvider aiProvider) {
        log.debug("Sending AI explanation request for analysis");
        return restClient
                .generateExplanation(analysisResult, aiProvider)
                .onItem()
                .invoke(response -> log.debug("Received AI explanation response"))
                .onFailure()
                .invoke(throwable -> log.error("AI explanation generation failed", throwable));
    }
}
