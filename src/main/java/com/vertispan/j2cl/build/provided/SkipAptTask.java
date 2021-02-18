package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.OutputTypes;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.TaskFactory;

/**
 * Disables annotation processors from within this build, and relies instead on some other tooling
 * already generating sources (hopefully incrementally). By producing no sources and depending on
 * nothing it will run quickly and be cached after the first time even if inputs change - and without
 * it, the "extra" non-stripped bytecode task is unnecessary.
 */
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
