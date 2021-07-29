package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.task.*;

import java.nio.file.Files;

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
        Input myStrippedBytecode = input(project, OutputTypes.STRIPPED_BYTECODE);
        return output -> {

            // for now we're going to just copy the bytecode
            for (CachedPath path : myStrippedBytecode.getFilesAndHashes()) {
                Files.createDirectories(output.path().resolve(path.getSourcePath()).getParent());
                Files.copy(path.getAbsolutePath(), output.path().resolve(path.getSourcePath()));
            }
        };
    }
}
