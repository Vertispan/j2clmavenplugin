package com.vertispan.j2cl.build.task;

import java.nio.file.Path;
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
 * @todo add a version property so we can tell when it changes
 */
public abstract class TaskFactory {
//    private static ThreadLocal<CollectedTaskInputs> collectorForThread = new ThreadLocal<>();

//    @Deprecated//TODO not this
//    public static void setCollectorForThread(CollectedTaskInputs collectorForThread) {
//        //TODO inject into Input instances instead
//        TaskFactory.collectorForThread.set(collectorForThread);
//    }

    protected Input input(Dependency dependency, String outputType) {
        return input(dependency.getProject(), outputType);
    }
    protected Input input(Project dependencyProject, String outputType) {
        return new com.vertispan.j2cl.build.Input((com.vertispan.j2cl.build.Project) dependencyProject, outputType);
    }
    protected Function<Project, Input> inputs(String outputType) {
        return p -> input(p, outputType);
    }

    protected List<Project> scope(Collection<? extends Dependency> dependencies, Dependency.Scope scope) {
        return dependencies.stream()
                .filter(d -> d.getScope() == scope)
                .map(Dependency::getProject)
                .collect(Collectors.toList());
    }

    /**
     * @todo consider removing this, just assume that the name in the registry is enough to know what it is for?
     */
    public abstract String getOutputType();

    /**
     * The name to look for in configuration to specify an implementation
     */
    public abstract String getTaskName();

    /**
     * Complete the work, based on the inputs requested and the configs accessed.
     */
    @FunctionalInterface
    public interface Task {
        void execute(Path outputPath) throws Exception;
    }

    /**
     * Interface to indicate that there is a bit more work to do to collect the
     * actual output to be consumed. This work (probably) needs to be done
     * regardless of cache state since the output path is likely outside of our
     * control. As a result, config properties can be used here and are not
     * required to be made part of the cache key.
     *
     * Subject to change: the output path will still be provided, no need to read that from config
     */
    public interface FinalOutputTask extends Task {
        void finish();
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
