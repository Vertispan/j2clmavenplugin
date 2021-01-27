package net.cardosi.mojo.build;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Looks for tasks which are available on the current classpath and offers
 * the currently selected implementations
 */
public class TaskRegistry {
    private final Map<String, Task> outputTypeToTaskMappings = new HashMap<>();

    /**
     * Create with the default mappings, which will be looked up in the service loader.
     * @param outputToNameMappings configured mappings provided from the build tool
     */
    public TaskRegistry(Map<String, String> outputToNameMappings) {
        ServiceLoader<Task> loader = ServiceLoader.load(Task.class);
        for (Task task : loader) {
            if (task.getTaskName().equals(outputToNameMappings.get(task.getOutputType()))) {
                task.init(this);
                outputTypeToTaskMappings.put(task.getOutputType(), task);
            }
        }
    }

    public Task taskForOutputType(String outputType) {
        return outputTypeToTaskMappings.get(outputType);
    }


    public Path resolvePath(Project project, String outputType) {
        return null;
    }
}
