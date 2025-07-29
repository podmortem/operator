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

/**
 * Client service for communicating with the AI Interface REST API.
 *
 * <p>Handles the conversion of Kubernetes AI Provider Custom Resources to AI provider
 * configurations, manages authentication credential retrieval, and facilitates AI explanation
 * generation for pod failure analysis.
 */
@ApplicationScoped
public class AIInterfaceClient {

    private static final Logger log = LoggerFactory.getLogger(AIInterfaceClient.class);

    @Inject @RestClient AIInterfaceRestClient restClient;

    @Inject KubernetesClient kubernetesClient;

    /**
     * Generates an AI explanation for a pod failure analysis result.
     *
     * <p>Converts the AIProvider CRD to an AIProviderConfig, creates an analysis request, and
     * submits it to the AI interface service for explanation generation.
     *
     * @param analysisResult the log pattern analysis results to explain
     * @param aiProvider the AI provider configuration from Kubernetes CRD
     * @return a Uni that emits the AI-generated explanation response
     */
    public Uni<AIResponse> generateExplanation(
            AnalysisResult analysisResult, AIProvider aiProvider) {
        log.debug("Sending AI explanation request for analysis");

        AIProviderConfig providerConfig = convertToProviderConfig(aiProvider);

        AnalysisRequest request = new AnalysisRequest(analysisResult, providerConfig);

        return restClient
                .analyzeFailure(request)
                .onItem()
                .invoke(response -> log.debug("Received AI explanation response"))
                .onFailure()
                .invoke(throwable -> log.error("AI explanation generation failed", throwable));
    }

    /**
     * Converts an AIProvider Kubernetes CRD to an AIProviderConfig for the AI interface.
     *
     * <p>Maps CRD specification fields to the configuration format expected by the AI interface
     * service, including loading authentication credentials from Kubernetes secrets when
     * configured.
     *
     * @param aiProvider the AI provider CRD to convert
     * @return an AIProviderConfig object for AI interface consumption
     */
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

        if (spec.getAdditionalConfig() != null) {
            config.setAdditionalHeaders(spec.getAdditionalConfig());
        }

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

    /**
     * Loads an authentication token from a Kubernetes secret.
     *
     * <p>Retrieves and decodes authentication credentials from Kubernetes secret data, supporting
     * base64 encoded values as typically stored in secrets.
     *
     * @param namespace the namespace containing the secret
     * @param secretName the name of the secret
     * @param secretKey the key within the secret containing the authentication token
     * @return the decoded authentication token, or null if not found or on error
     */
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
