package com.vertispan.j2cl.build.impl;

import com.vertispan.j2cl.build.Input;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.TaskFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Badly named.
 *
 * This is meant to represent the conjunction of things that tell the build tooling where to put
 * the output from a task - by knowing about its inputs we can produce a hash, and that will tell
 * us where on disk to look to see if we've already produced the required output for the task. If
 * it already exists, we don't create it again, if not it will be placed there for future use.
 *
 * The Project isn't strictly needed to produce the hash (the inputs will include any references
 * required to the project itself), but does help in making human readable directory structures.
 *
 * The TaskFactory and its taskName property also help with human readability of outputs, and
 * ensure that even given the same inputs, more than one task won't automatically resolve to the
 * same output, since that wouldn't make sense.
 *
 * The inputs are a list of inputs - ordered is presumed to be stable by the TaskFactory that
 * produced it.
 *
 * The configuration map is sorted by input key. Structure of inputs are a work in progress.
 */
public class CollectedTaskInputs {
    private final Project project;
    private TaskFactory taskFactory;
    private List<Input> inputs;
    private Map<String, String> usedConfigs;

    // not used for anything except kept around to avoid recomputing it
    private TaskFactory.Task task;

    public CollectedTaskInputs(Project project) {
        this.project = project;
    }

    public List<Input> getInputs() {
        return inputs;
    }

    public void setInputs(List<Input> inputs) {
        this.inputs = inputs;
    }

    public Project getProject() {
        return project;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CollectedTaskInputs that = (CollectedTaskInputs) o;

        if (!project.equals(that.project)) return false;
        if (!taskFactory.equals(that.taskFactory)) return false;
        if (!inputs.equals(that.inputs)) return false;
        return usedConfigs.equals(that.usedConfigs);
    }

    @Override
    public int hashCode() {
        int result = project.hashCode();
        result = 31 * result + taskFactory.hashCode();
        result = 31 * result + inputs.hashCode();
        result = 31 * result + usedConfigs.hashCode();
        return result;
    }

    public void setTask(TaskFactory.Task task) {
        this.task = task;
    }

    public TaskFactory.Task getTask() {
        return task;
    }
}
