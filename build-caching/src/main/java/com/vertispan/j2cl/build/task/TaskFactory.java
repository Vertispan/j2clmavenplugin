package com.vertispan.j2cl.build.task;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A task describes the type of output it provides, and for a given project will provide the
 * inputs it needs (its "dependencies", for want of a better phrase), and a way to start the
 * work once those can be resolved.
 *
 * Task implementations can be registered to replace the default wiring.
 *
 * This is part of the public API, new implementations can be provided, even for output types
 * that aren't known by the plugin.
 *
 */
public abstract class TaskFactory {
    protected static PathMatcher withSuffix(String suffix) {
        return new PathMatcher() {
            @Override
            public boolean matches(Path p) {
                return p.getFileName().toString().endsWith(suffix);
            }

            @Override
            public String toString() {
                return "Filenames that end with " + suffix;
            }
        };
    }

    public final List<com.vertispan.j2cl.build.Input> inputs = new ArrayList<>();

    protected Input input(Dependency dependency, String outputType) {
        return input(dependency.getProject(), outputType);
    }
    protected Input input(Project dependencyProject, String outputType) {
        com.vertispan.j2cl.build.Input i = new com.vertispan.j2cl.build.Input((com.vertispan.j2cl.build.Project) dependencyProject, outputType);
        inputs.add(i);
        return i;
    }
    protected Function<Project, Input> inputs(String outputType) {
        return p -> input(p, outputType);
    }

    protected List<Project> scope(Collection<? extends Dependency> dependencies, Dependency.Scope scope) {
        return dependencies.stream()
                .filter(d -> ((com.vertispan.j2cl.build.Dependency) d).belongsToScope(scope))
                .map(Dependency::getProject)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * @return the output type that this task factory can emit
     */
    // TODO Consider removing this, just assume that the name in the registry is enough to know what it is for?
    public abstract String getOutputType();

    /**
     * @return The name to look for in configuration to specify an implementation
     */
    public abstract String getTaskName();

    /**
     * Some string identifier for the current version of this task. Ideally when the task's contents change in some way
     * that would affect output, this should change as well.
     */
    public abstract String getVersion();


    /**
     * Complete the work, based on the inputs requested and the configs accessed.
     */
    @FunctionalInterface
    public interface Task {
        void execute(TaskContext context) throws Exception;
    }

    /**
     * Interface to indicate that there is a bit more work to do to collect the
     * actual output to be consumed. This work (probably) needs to be done
     * regardless of cache state since the output path is likely outside of our
     * control. As a result, config properties can be used here and are not
     * required to be made part of the cache key.
     *
     * Parameters are subject to change - webappDir should be read from the config
     * object, as this won't affect the task hash, and the output from when the
     * task last ran will be available as a parameter.
     */
    public interface FinalOutputTask extends Task {
        void finish(TaskContext taskContext) throws Exception;
    }

    /**
     * Subclasses implement this method, and return a customized lambda
     * which can do the work required for this task. By creating inputs
     * from the given project when this method is invoked, it will signal
     * to the plugin what it depends on and how, and will allow the output
     * for this task to be correctly cached.
     *
     * The input methods can only be called while this method is being
     * invoked, and files should only be read from those instances.
     * @param project the project to evaluate and construct a task for
     * @param config the current configuration
     * @return a task that will be executed each time the given project
     * needs to be built, which should use created inputs and configs
     */
    public abstract Task resolve(Project project, Config config);

}
