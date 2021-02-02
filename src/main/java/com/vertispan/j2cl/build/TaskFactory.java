package com.vertispan.j2cl.build;

import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.impl.CollectedTaskInputs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A task describes the type of output it provides, and for a given project will provide the
 * inputs it needs (its "dependencies", for want of a better phrase), and a way to start the
 * work once those can be resolved.
 *
 * Task implementations can be registered to replace the default wiring.
 */
public abstract class TaskFactory {
    private static ThreadLocal<CollectedTaskInputs> collectorForThread = new ThreadLocal<>();

    public static void setCollectorForThread(CollectedTaskInputs collectorForThread) {
        //TODO inject into Input instances instead
        TaskFactory.collectorForThread.set(collectorForThread);
    }

    private TaskRegistry registry;

    protected final void init(TaskRegistry registry) {
        this.registry = registry;
    }
    protected final TaskRegistry getRegistry() {
        return registry;
    }
    protected Input input(Dependency dependency, String outputType) {
        return input(dependency.getProject(), outputType);
    }
    protected Input input(Project dependencyProject, String outputType) {
        return new Input(dependencyProject, outputType);
    }
    protected Function<Project, Input> inputs(String outputType) {
        return p -> input(p, outputType);
    }

    protected List<Project> scope(List<Dependency> dependencies, Dependency.Scope scope) {
        return dependencies.stream()
                .filter(d -> d.getScope() == scope)
                .map(Dependency::getProject)
                .collect(Collectors.toList());
    }

    protected List<SourceUtils.FileInfo> getFileInfoInDir(Path dir, PathMatcher... matcher) {
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }
        try {
            return Files.find(dir, Integer.MAX_VALUE, ((path, basicFileAttributes) -> Arrays.stream(matcher).anyMatch(m -> m.matches(path))))
                    .map(p -> SourceUtils.FileInfo.create(p.toString(), dir.toAbsolutePath().relativize(p).toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public abstract String getOutputType();
    public abstract String getTaskName();

    @FunctionalInterface
    public interface Task {
        void execute(Path outputPath) throws Exception;
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
