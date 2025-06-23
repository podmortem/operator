package com.redhat.podmortem.model.cr.config;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import java.util.List;

public class PodFailureData {
    private Pod pod;
    private String logs;
    private List<Event> events;

    public PodFailureData(Pod pod, String logs, List<Event> events) {
        this.pod = pod;
        this.logs = logs;
        this.events = events;
    }

    // Getters and setters
    public Pod getPod() { return pod; }
    public void setPod(Pod pod) { this.pod = pod; }
    public String getLogs() { return logs; }
    public void setLogs(String logs) { this.logs = logs; }
    public List<Event> getEvents() { return events; }
    public void setEvents(List<Event> events) { this.events = events; }
}