package dev.navigationai.api;

public interface ResourceCollector {
    GatherTask gather(GatherRequest request, GatherPolicy policy);
    GatherStatus status();
    GatherTask task();
}
