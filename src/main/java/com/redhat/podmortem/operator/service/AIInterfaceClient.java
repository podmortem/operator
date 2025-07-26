package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.analysis.AnalysisRequest;
import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.aiprovider.AIProvider;
import com.redhat.podmortem.common.model.provider.AIProviderConfig;
import com.redhat.podmortem.common.model.provider.AIResponse;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Base64;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AIInterfaceClient {

    private static final Logger log = LoggerFactory.getLogger(AIInterfaceClient.class);

    @Inject @RestClient AIInterfaceRestClient restClient;

    @Inject KubernetesClient kubernetesClient;

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

    /** Convert AIProvider CRD to AIProviderConfig for AI interface */
    private AIProviderConfig convertToProviderConfig(AIProvider aiProvider) {
        AIProviderConfig config = new AIProviderConfig();

        var spec = aiProvider.getSpec();
        config.setProviderId(spec.getProviderId());
        config.setApiUrl(spec.getApiUrl());
        config.setModelId(spec.getModelId());
        config.setTimeoutSeconds(spec.getTimeoutSeconds() != null ? spec.getTimeoutSeconds() : 30);
        config.setMaxRetries(spec.getMaxRetries() != null ? spec.getMaxRetries() : 3);
        config.setCachingEnabled(
                spec.getCachingEnabled() != null ? spec.getCachingEnabled() : true);
        config.setPromptTemplate(spec.getPromptTemplate());
        config.setMaxTokens(spec.getMaxTokens() != null ? spec.getMaxTokens() : 500);
        config.setTemperature(spec.getTemperature() != null ? spec.getTemperature() : 0.3);

        // Convert additional config to appropriate format
        if (spec.getAdditionalConfig() != null) {
            config.setAdditionalHeaders(spec.getAdditionalConfig());
        }

        // Load authentication token from secret if configured
        if (spec.getAuthenticationRef() != null) {
            String authToken =
                    loadAuthTokenFromSecret(
                            aiProvider.getMetadata().getNamespace(),
                            spec.getAuthenticationRef().getSecretName(),
                            spec.getAuthenticationRef().getSecretKey());
            config.setAuthToken(authToken);
            log.debug(
                    "Loaded auth token from secret {}/{}",
                    aiProvider.getMetadata().getNamespace(),
                    spec.getAuthenticationRef().getSecretName());
        }

        log.debug("Converted AIProvider {} to AIProviderConfig", spec.getProviderId());
        return config;
    }

    /** Load authentication token from Kubernetes secret */
    private String loadAuthTokenFromSecret(String namespace, String secretName, String secretKey) {
        try {
            Secret secret =
                    kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get();

            if (secret == null) {
                log.error("Authentication secret not found: {}/{}", namespace, secretName);
                return null;
            }

            Map<String, String> data = secret.getData();
            if (data == null || !data.containsKey(secretKey)) {
                log.error(
                        "Authentication key '{}' not found in secret {}/{}",
                        secretKey,
                        namespace,
                        secretName);
                return null;
            }

            // Decode base64 encoded secret data
            String encodedToken = data.get(secretKey);
            return new String(Base64.getDecoder().decode(encodedToken));

        } catch (Exception e) {
            log.error(
                    "Failed to load auth token from secret {}/{}: {}",
                    namespace,
                    secretName,
                    e.getMessage());
            return null;
        }
    }
}
