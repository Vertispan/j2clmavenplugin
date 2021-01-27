package net.cardosi.mojo.build.provided;

import com.google.auto.service.AutoService;
import net.cardosi.mojo.build.*;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@AutoService(Task.class)
public class AptTask extends Task {

    @Override
    public String getOutputType() {
        return OutputTypes.GENERATED_SOURCES;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public List<Input> resolveInputs(Project project) {
        if (!project.hasSourcesMapped()) {
            // the jar exists, use the existing bytecode
            return Collections.emptyList();
        }

        return Collections.singletonList(input(project, OutputTypes.BYTECODE));
    }

    @Override
    public void complete(Project project, Path output) throws Exception {
        if (!project.hasSourcesMapped()) {
            // the jar exists, use the existing bytecode
            return;
        }

        // the BytecodeTask already did the work for us, just copy sources to output
        System.out.println("TODO " + getClass());
    }
}
