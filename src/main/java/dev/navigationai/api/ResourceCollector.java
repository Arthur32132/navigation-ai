package dev.navigationai.api;

public interface ResourceCollector {
    GatherTask gather(GatherRequest request, GatherPolicy policy);
    /** Uses the supplied policy for approaches to resources; supported by the built-in collector. */
    default GatherTask gather(GatherRequest request, GatherPolicy policy, NavigationPolicy navigationPolicy) {
        throw new UnsupportedOperationException("This collector does not support custom navigation policy");
    }
    GatherStatus status();
    GatherTask task();
}
