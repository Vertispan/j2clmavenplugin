package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.task.*;

/**
 * TODO implement using JsChecker
 *
 * For now, this is just the same output, straight passthru
 */
@AutoService(TaskFactory.class)
public class IJsTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return OutputTypes.TRANSPILED_JS_HEADERS;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        Input js = input(project, OutputTypes.TRANSPILED_JS);
        return context -> {
            if (js.getFilesAndHashes().isEmpty()) {
                // nothing to do
            }

            // for now we're going to just copy the JS
            context.warn("TODO " + getClass());

        };
    }
}
