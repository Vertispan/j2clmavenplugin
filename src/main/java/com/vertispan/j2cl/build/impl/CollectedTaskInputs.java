package com.vertispan.j2cl.build.impl;

import com.vertispan.j2cl.build.Input;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.TaskFactory;

import java.util.Map;
import java.util.Set;

public class CollectedTaskInputs {
    private final String finalTask;
    private final Project root;
    private TaskFactory taskFactory;
    private TaskFactory.Task task;
    private Set<Input> inputs;
    private Map<String, String> usedConfigs;

    public CollectedTaskInputs(String finalTask, Project root) {

        this.finalTask = finalTask;
        this.root = root;
    }

    public void setTask(TaskFactory.Task task) {
        this.task = task;
    }

    public TaskFactory.Task getTask() {
        return task;
    }

    public Set<Input> getInputs() {
        return inputs;
    }

    public void setInputs(Set<Input> inputs) {
        this.inputs = inputs;
    }

    public String getFinalTask() {
        return finalTask;
    }

    public Project getRoot() {
        return root;
    }

    public TaskFactory getTaskFactory() {
        return taskFactory;
    }

    public Map<String, String> getUsedConfigs() {
        return usedConfigs;
    }

    public void setTaskFactory(TaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    public void setUsedConfigs(Map<String, String> usedConfigs) {
        this.usedConfigs = usedConfigs;
    }
}
