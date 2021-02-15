package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.OutputTypes;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.TaskFactory;

@AutoService(TaskFactory.class)
public class SkipAptTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.GENERATED_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "skip";
    }

    @Override
    public Task resolve(Project project, PropertyTrackingConfig config) {
        return outputPath -> {};
    }
}
