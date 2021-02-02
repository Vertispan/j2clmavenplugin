package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.Config;
import com.vertispan.j2cl.build.OutputTypes;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.TaskFactory;

/**
 * TODO implement using the ijar tool or the equivelent
 *
 * For now, this is just the same output, straight passthru
 */
@AutoService(TaskFactory.class)
public class IJarTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE_HEADERS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        input(project, OutputTypes.STRIPPED_BYTECODE);
        return outputPath -> {

            // for now we're going to just copy the bytecode
            System.out.println("TODO " + getClass());

        };
    }
}
