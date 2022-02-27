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
    // While this is an internal task, it is still possible to provide an alternative implementation
    public static final String JSZIP_BUNDLE_OUTPUT_TYPE = "jszipbundle";

    @Override
    public String getOutputType() {
        return JSZIP_BUNDLE_OUTPUT_TYPE;
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
        // we actually ignore project here, and just read from config
        List<File> extraJsZips = config.getExtraJsZips();
        return context -> {
            Closure closureCompiler = new Closure(context);

            boolean success = closureCompiler.compile(
                    CompilationLevel.BUNDLE,
                    DependencyOptions.DependencyMode.SORT_ONLY,
                    CompilerOptions.LanguageMode.NO_TRANSPILE,
                    Collections.emptyMap(),
                    null,
                    extraJsZips,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    config.getTranslationsFile(),
                    null,
                    true,
                    true,
                    false,
                    false,
                    "CUSTOM", // doesn't matter, bundle won't check this
                    context.outputPath().resolve("j2cl-base.js").toString()
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }
        };
    }
}
