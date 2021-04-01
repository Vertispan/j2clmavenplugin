package net.cardosi.mojo.tools;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.parsing.Config;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;

public class Closure {
    /**
     * Closure compiler uses static variables at least when parsing arguments, so we have to ensure
     * that only one thread at a time is trying to parse args, otherwise we risk CMEs. The comments
     * around this code appear to indicate that multiple instances of the compiler at a time are
     * safe as long as they share "flags", but it is a bit worse - the subsequent instances must
     * actually share the options instance itself.
     */
    private static final Object GLOBAL_CLOSURE_ARGS_LOCK = new Object();

    public boolean compile(
            CompilationLevel compilationLevel,
            DependencyOptions.DependencyMode dependencyMode,
            CompilerOptions.LanguageMode languageOut,
            @Nullable File jsSourceDir,
            List<File> jsZips,
            List<String> entrypoints,
            Map<String, String> defines,
            Collection<String> externFiles,
            PersistentInputStore persistentInputStore,
            boolean exportTestFunctions,
            boolean checkAssertions,
            boolean rewritePolyfills,
            boolean enabledSourcemaps,
            String jsOutputFile
    ) {
        List<String> jscompArgs = new ArrayList<>();

        Compiler jsCompiler = new Compiler(System.err);
//        jsCompiler.setPersistentInputStore(persistentInputStore);

        if (jsSourceDir != null) {
            //TODO stop allowing null, instead force caller to enumerate the files like
            //     we do elsewhere
            jscompArgs.add("--js");
            jscompArgs.add(jsSourceDir + "/**/*.js");
        }

        jsZips.forEach(file -> {
            jscompArgs.add("--jszip");
            jscompArgs.add(file.getAbsolutePath());
        });

        for (Map.Entry<String, String> define : defines.entrySet()) {
            jscompArgs.add("--define");
            jscompArgs.add(define.getKey() + "=" + define.getValue());
        }

        for (String extern : externFiles) {
            jscompArgs.add("--externs");
            jscompArgs.add(extern);
        }

        jscompArgs.add("--compilation_level");
        jscompArgs.add(compilationLevel.name());

        jscompArgs.add("--dependency_mode");
        jscompArgs.add(dependencyMode.name());

        jscompArgs.add("--language_out");
        jscompArgs.add(languageOut.name());

        jscompArgs.addAll(Arrays.asList(//TODO parameterize?
                "--jscomp_off",
                "analyzerChecks",
//                    "--jscomp_off",
//                    "JSC_UNKNOWN_EXPR_TYPE",
//                    "--jscomp_off",
//                    "JSC_STRICT_INEXISTENT_PROPERTY",
                "--rewrite_polyfills=" + rewritePolyfills
        ));

        if (enabledSourcemaps && jsSourceDir != null) {
            jscompArgs.add("--create_source_map");
            jscompArgs.add(jsOutputFile + ".map");
//            jscompArgs.add("%outname%.map"); // this variant would allow one sourcemap per chunk

            jscompArgs.add("--source_map_location_mapping");
            jscompArgs.add(jsSourceDir.getParent() + "|.");// we use parent since the source dir always has a source/ suffix

            jscompArgs.add("--output_wrapper");
            jscompArgs.add("(function(){%output%}).call(this);\n//# sourceMappingURL="+jsOutputFile.substring(jsOutputFile.lastIndexOf("/") + 1)+".map");
            jscompArgs.add("--assume_function_wrapper");
            jscompArgs.add("true");


            //TODO deal with chunk_wrapper here, once we support those. might be as simple as this (plus knowledge of the chunk names):
//            jscompArgs.add("--chunk_wrapper");
//            jscompArgs.add("NAME_OF_CHUNK_HERE:%output%%n//# sourceMappingURL=%basename%.map");
        } else if (compilationLevel == CompilationLevel.ADVANCED_OPTIMIZATIONS) {
            // go ahead and use IIFE
            jscompArgs.add("--isolation_mode");
            jscompArgs.add("IIFE");
        }

        for (String entrypoint : entrypoints) {
            jscompArgs.add("--entry_point");
            jscompArgs.add(entrypoint);
        }

        jscompArgs.add("--js_output_file");
        jscompArgs.add(jsOutputFile);

        //TODO bundles

        final InProcessJsCompRunner jscompRunner;
        synchronized (GLOBAL_CLOSURE_ARGS_LOCK) {
            jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler, exportTestFunctions, checkAssertions);
        }
        if (!jscompRunner.shouldRunCompiler()) {
            jscompArgs.forEach(System.out::println);
            return false;
        }

        //TODO historically we didnt populate the persistent input store until this point, so put it here
        //     if we restore it

        try {
            jscompRunner.run();

            if (jscompRunner.hasErrors() || jscompRunner.exitCode != 0) {
                jscompArgs.forEach(System.out::println);
                return false;
            }
        } finally {
            if (jsCompiler.getModules() != null) {
                // clear out the compiler input for the next go-around
                jsCompiler.resetCompilerInput();
            }
        }
        return true;
    }

    static class InProcessJsCompRunner extends CommandLineRunner {
        private final boolean exportTestFunctions;
        private final boolean checkAssertions;

        private final Compiler compiler;
        private Integer exitCode;

        InProcessJsCompRunner(String[] args, Compiler compiler, boolean exportTestFunctions, boolean checkAssertions) {
            super(args);
            this.compiler = compiler;
            this.exportTestFunctions = exportTestFunctions;
            this.checkAssertions = checkAssertions;
            setExitCodeReceiver(exitCode -> {
                this.exitCode = exitCode;
                return null;
            });
        }

        @Override
        protected Compiler createCompiler() {
            return compiler;
        }

        @Override
        protected CompilerOptions createOptions() {
            CompilerOptions options = super.createOptions();

//            options.addWarningsGuard();

            options.setExportTestFunctions(exportTestFunctions);

            options.setRemoveJ2clAsserts(!checkAssertions);

            return options;
        }
    }


}