package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisRequest;
import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.provider.AIProviderConfig;
import com.redhat.podmortem.common.model.provider.AIResponse;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

@ApplicationScoped
public class AIInterfaceClient {

    private static final Logger log = LoggerFactory.getLogger(AIInterfaceClient.class);

    @Inject @RestClient AIInterfaceRestClient restClient;

    public Uni<AIResponse> generateExplanation(
            AnalysisResult analysisResult, AIProvider aiProvider) {
        log.debug("Sending AI explanation request for analysis");
        
        // Convert AIProvider CRD to AIProviderConfig
        AIProviderConfig providerConfig = convertToProviderConfig(aiProvider);
        
        // Create AnalysisRequest
        AnalysisRequest request = new AnalysisRequest(analysisResult, providerConfig);
        
        return restClient
                .explainFailure(request)
                .onItem()
                .invoke(response -> log.debug("Received AI explanation response"))
                .onFailure()
                .invoke(throwable -> log.error("AI explanation generation failed", throwable));
    }

    /**
     * Convert AIProvider CRD to AIProviderConfig for AI interface
     */
    private AIProviderConfig convertToProviderConfig(AIProvider aiProvider) {
        AIProviderConfig config = new AIProviderConfig();
        
        var spec = aiProvider.getSpec();
        config.setProviderId(spec.getProviderId());
        config.setApiUrl(spec.getApiUrl());
        config.setModelId(spec.getModelId());
        config.setTimeoutSeconds(spec.getTimeoutSeconds() != null ? spec.getTimeoutSeconds() : 30);
        config.setMaxRetries(spec.getMaxRetries() != null ? spec.getMaxRetries() : 3);
        config.setCachingEnabled(spec.getCachingEnabled() != null ? spec.getCachingEnabled() : true);
        config.setPromptTemplate(spec.getPromptTemplate());
        config.setMaxTokens(spec.getMaxTokens() != null ? spec.getMaxTokens() : 500);
        config.setTemperature(spec.getTemperature() != null ? spec.getTemperature() : 0.3);
        
        // Convert additional config to appropriate format
        if (spec.getAdditionalConfig() != null) {
            config.setAdditionalHeaders(spec.getAdditionalConfig());
        }
        
        // Note: Authentication token would need to be resolved from secret reference
        // For now, we'll skip this as it requires additional Kubernetes API calls
        // In production, this should be handled properly
        
        log.debug("Converted AIProvider {} to AIProviderConfig", spec.getProviderId());
        return config;
    }
}
