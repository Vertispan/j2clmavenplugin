package net.cardosi.mojo.build.provided;

import com.google.auto.service.AutoService;
import net.cardosi.mojo.build.Input;
import net.cardosi.mojo.build.OutputTypes;
import net.cardosi.mojo.build.Project;
import net.cardosi.mojo.build.Task;

import java.nio.file.Path;
import java.util.List;

@AutoService(Task.class)
public class ClosureTask extends Task {
    @Override
    public String getOutputType() {
        return OutputTypes.OPTIMIZED_JS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public List<Input> resolveInputs(Project project) {
        return null;
    }

    @Override
    public void complete(Project project, Path output) throws Exception {
        System.out.println("TODO " + getClass());

    }
}
