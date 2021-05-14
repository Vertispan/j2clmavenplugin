package com.vertispan.j2cl.build.provided;

import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.Dependency;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.TaskFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BundleJarTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.BUNDLED_JS_APP;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        List<Input> jsSources = Stream.concat(
                Stream.of(input(project, OutputTypes.BUNDLED_JS)),
                scope(project.getDependencies(), Dependency.Scope.RUNTIME)
                        .stream()
                        .map(inputs(OutputTypes.BUNDLED_JS))
        ).collect(Collectors.toList());

        return new FinalOutputTask() {
            @Override
            public void execute(Path outputPath) throws Exception {

            }

            @Override
            public void finish(Path taskOutput) {
                Path webappDirectory = config.getWebappDirectory();
            }
        };
    }
}
