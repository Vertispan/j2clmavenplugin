package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.*;

@AutoService(TaskFactory.class)
public class StripSourcesTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        Input inputSources = input(project, OutputTypes.INPUT_SOURCES);
        Input generatedSources = input(project, OutputTypes.GENERATED_SOURCES);
        return outputPath -> {
            System.out.println("TODO " + getClass());
        };
    }
}
