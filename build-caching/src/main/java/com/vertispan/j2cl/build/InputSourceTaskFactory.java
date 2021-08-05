package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;

/**
 * No-op task factory to use to set up input sources as real tasks. Internal use only.
 */
public class InputSourceTaskFactory extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.INPUT_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "internal-only";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        return ignore -> {};
    }
}
