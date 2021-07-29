package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;
import com.vertispan.j2cl.tools.Closure;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Special task that takes all jszips and makes a single bundle file from them. Might not be necessary, if we
 * can treat each js zip like its own jar and just bundle that way once.
 */
@AutoService(TaskFactory.class)
public class JsZipBundleTask extends TaskFactory {
    @Override
    public String getOutputType() {
        return "jszipbundle";
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // we actually ignore project here, and just read from config
        List<File> extraJsZips = config.getExtraJsZips();
        return output -> {
            Closure closureCompiler = new Closure();

            boolean success = closureCompiler.compile(
                    CompilationLevel.BUNDLE,
                    DependencyOptions.DependencyMode.SORT_ONLY,
                    CompilerOptions.LanguageMode.NO_TRANSPILE,
                    Collections.emptyList(),
                    null,
                    extraJsZips,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    null,
                    true,
                    true,
                    false,
                    false,
                    output.path().resolve("j2cl-base.js").toString()
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }
        };
    }
}
