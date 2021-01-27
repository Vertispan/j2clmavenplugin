package net.cardosi.mojo.build.provided;

import com.google.auto.service.AutoService;
import net.cardosi.mojo.build.Input;
import net.cardosi.mojo.build.OutputTypes;
import net.cardosi.mojo.build.Project;
import net.cardosi.mojo.build.Task;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * TODO implement using JsChecker
 *
 * For now, this is just the same output, straight passthru
 */
@AutoService(Task.class)
public class IJsTask extends Task {
    @Override
    public String getOutputType() {
        return OutputTypes.TRANSPILED_JS_HEADERS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public List<Input> resolveInputs(Project project) {
        return Collections.singletonList(input(project, OutputTypes.TRANSPILED_JS));
    }

    @Override
    public void complete(Project project, Path output) throws Exception {
        //no-op?
    }
}
