package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.impl.CollectedTaskInputs;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Looks for tasks which are available on the current classpath and offers
 * the currently selected implementations
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
                task.init(this);
                outputTypeToTaskMappings.put(task.getOutputType(), task);
            }
        }
    }

    /**
     * Called
     * @param finalTask
     * @param root
     * @param config
     */
    public void collectTasksFromProject(String finalTask, Project root, Map<String, Object> config) {
        collectTasksFromProject(finalTask, root, config, new LinkedHashMap<>());
    }
    private void collectTasksFromProject(String finalTask, Project root, Map<String, Object> config, Map<Input, CollectedTaskInputs> collectedSoFar) {
        Input newInput = new Input(root, finalTask);
        if (collectedSoFar.containsKey(newInput)) {
            // don't build a step twice
//            return collectedSoFar.get(new Input(root, finalTask));
            return;
        }
        CollectedTaskInputs collectedInputs = new CollectedTaskInputs(finalTask, root);
        TaskFactory.setCollectorForThread(collectedInputs);
        PropertyTrackingConfig propertyTrackingConfig = new PropertyTrackingConfig(config);
        TaskFactory.Task task = taskForOutputType(finalTask).resolve(root, propertyTrackingConfig);
        collectedInputs.setTask(task);
        collectedInputs.setUsedConfigs(propertyTrackingConfig.getUsedConfigs());
        collectedSoFar.put(newInput, collectedInputs);
        for (Input input : collectedInputs.getInputs()) {
            collectTasksFromProject(input.getOutputType(), input.getProject(), config, collectedSoFar);
        }
    }

    public TaskFactory taskForOutputType(String outputType) {
        return outputTypeToTaskMappings.get(outputType);
    }


    public Path resolvePath(Project project, String outputType) {
        return null;
    }
}
