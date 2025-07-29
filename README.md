# operator

A Quarkus-based Kubernetes operator for managing pod failure analysis in the Podmortem operator.

## Overview

This operator manages the lifecycle of Podmortem Custom Resources and orchestrates pod failure analysis workflows. It provides real-time pod monitoring, pattern library synchronization, and coordinates between log parsing and AI analysis services.

## Custom Resources

The operator manages three main Custom Resource types:

### Podmortem

Configures pod failure monitoring for specific workloads:

```yaml
apiVersion: podmortem.redhat.com/v1
kind: Podmortem
metadata:
  name: quarkus-app-monitor
spec:
  podSelector:
    matchLabels:
      app: quarkus-app
  aiAnalysisEnabled: true
  aiProviderRef:
    name: openai-provider
    namespace: podmortem-system
```

### PatternLibrary

Manages synchronization of failure pattern definitions:

```yaml
apiVersion: podmortem.redhat.com/v1
kind: PatternLibrary
metadata:
  name: quarkus-patterns
spec:
  repositories:
    - name: core-patterns
      url: https://github.com/redhat/podmortem-patterns.git
      branch: main
  refreshInterval: "1h"
```

### AIProvider

Configures AI services for explanation generation:

```yaml
apiVersion: podmortem.redhat.com/v1
kind: AIProvider
metadata:
  name: openai-provider
spec:
  providerId: openai
  apiUrl: https://api.openai.com/v1
  modelId: gpt-3.5-turbo
  authenticationRef:
    secretName: openai-credentials
    secretKey: api-key
```

## Configuration

Key application properties:

```properties
# REST Client configurations
quarkus.rest-client.log-parser.url=http://log-parser:8080
quarkus.rest-client.ai-interface.url=http://ai-interface:8080

# Kubernetes client configuration
quarkus.kubernetes-client.trust-certs=true
quarkus.kubernetes-client.namespace=podmortem-system
```

## Dependencies

- `common-lib` - Shared models and Kubernetes CRD definitions
- **External Services**:
  - Log Parser service for pattern analysis
  - AI Interface service for explanation generation

## Building

```bash
./mvnw package
```

For native compilation:
```bash
./mvnw package -Dnative
```
