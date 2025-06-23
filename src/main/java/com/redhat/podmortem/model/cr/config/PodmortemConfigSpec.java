package com.redhat.podmortem.model.cr.config;

import io.fabric8.kubernetes.api.model.LabelSelector;

public class PodmortemConfigSpec {

    private LabelSelector podSelector;

    public LabelSelector getPodSelector() {
        return podSelector;
    }

    public void setPodSelector(LabelSelector podSelector) {
        this.podSelector = podSelector;
    }
}
