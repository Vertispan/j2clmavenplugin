package com.vertispan.j2cl.build;

// note that we don't bother to register this, it can't be overridden
public class InputSourceTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.INPUT_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, PropertyTrackingConfig config) {
        return outputPath -> {

        };
    }
}
