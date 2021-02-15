package com.vertispan.j2cl.build;

import java.nio.file.Path;
import java.nio.file.PathMatcher;

public class Input {
    private final Project project;
    private final String outputType;
//    private final PathMatcher[] filters;

    public Input(Project project, String outputType) {
        this.project = project;
        this.outputType = outputType;
    }

    public Input filter(PathMatcher... filters) {
        return this;
    }

    public Project getProject() {
        return project;
    }

    public String getOutputType() {
        return outputType;
    }

    public Path resolve(TaskRegistry registry) {
        return registry.resolvePath(project, outputType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Input input = (Input) o;

        if (!project.equals(input.project)) return false;
        return outputType.equals(input.outputType);
    }

    @Override
    public int hashCode() {
        int result = project.hashCode();
        result = 31 * result + outputType.hashCode();
        return result;
    }
}
