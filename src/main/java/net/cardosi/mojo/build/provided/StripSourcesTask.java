package net.cardosi.mojo.build.provided;

import com.google.auto.service.AutoService;
import net.cardosi.mojo.build.Input;
import net.cardosi.mojo.build.OutputTypes;
import net.cardosi.mojo.build.Project;
import net.cardosi.mojo.build.Task;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@AutoService(Task.class)
public class StripSourcesTask extends Task {
    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public List<Input> resolveInputs(Project project) {
        return Arrays.asList(
                input(project, OutputTypes.INPUT_SOURCES),
                input(project, OutputTypes.GENERATED_SOURCES)
        );
    }

    @Override
    public void complete(Project project, Path output) throws Exception {
        System.out.println("TODO " + getClass());
    }
}
