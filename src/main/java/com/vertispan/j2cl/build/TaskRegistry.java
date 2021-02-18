package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Looks for tasks which are available on the current classpath and offers
 * the currently selected implementations.
 *
 * Probably not part of the public API except to create an instance and
 * select mappings.
 */
public class TaskRegistry {
    private final Map<String, TaskFactory> outputTypeToTaskMappings = new HashMap<>();

    /**
     * Create with the default mappings, which will be looked up in the service loader.
     * @param outputToNameMappings configured mappings provided from the build tool
     */
    public TaskRegistry(Map<String, String> outputToNameMappings) {
        ServiceLoader<TaskFactory> loader = ServiceLoader.load(TaskFactory.class);
        for (TaskFactory task : loader) {
            if (task.getTaskName().equals(outputToNameMappings.get(task.getOutputType()))) {
                outputTypeToTaskMappings.put(task.getOutputType(), task);
            }
        }
    }

    public TaskFactory taskForOutputType(String outputType) {
        return outputTypeToTaskMappings.get(outputType);
    }


    public Path resolvePath(Project project, String outputType) {
        return null;
    }
}
