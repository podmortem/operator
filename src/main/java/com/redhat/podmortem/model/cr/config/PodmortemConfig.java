package com.redhat.podmortem.model.cr.config;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("podmortem.redhat.com")
@Version("v1alpha1")
public class PodmortemConfig extends CustomResource<PodmortemConfigSpec, PodmortemConfigStatus> implements Namespaced {
}