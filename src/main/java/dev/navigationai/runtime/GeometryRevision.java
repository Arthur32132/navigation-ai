package dev.navigationai.runtime;

/** Implemented by loaded chunks; revisions change for every successful block-state mutation. */
public interface GeometryRevision {
    long navigationAiGeometryRevision();
}
