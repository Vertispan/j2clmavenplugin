package net.cardosi.mojo.tools;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;

//TODO factor out the wiring to set this up for reuse
public class Closure {

    public boolean compile(
            CompilationLevel compilationLevel,
            DependencyOptions.DependencyMode dependencyMode,
            @Nullable File jsSourceDir,
            List<File> jsZips,
            List<String> entrypoints,
            Map<String, String> defines,
            Collection<String> externFiles,
            PersistentInputStore persistentInputStore,
            boolean exportTestFunctions,
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
        jscompArgs.add("ECMASCRIPT5");//TODO parameterize?

        jscompArgs.addAll(Arrays.asList(//TODO parameterize?
                "--jscomp_off",
                "analyzerChecks"
//                    "--jscomp_off",
//                    "JSC_UNKNOWN_EXPR_TYPE",
//                    "--jscomp_off",
//                    "JSC_STRICT_INEXISTENT_PROPERTY"
        ));

        for (String entrypoint : entrypoints) {
            jscompArgs.add("--entry_point");
            jscompArgs.add(entrypoint);
        }

        jscompArgs.add("--js_output_file");
        jscompArgs.add(jsOutputFile);

        //TODO bundles

        InProcessJsCompRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler,exportTestFunctions);
        if (!jscompRunner.shouldRunCompiler()) {
            jscompArgs.forEach(System.out::println);
            return false;
        }

        //TODO historically we didnt populate the persistent input store until this point, so put it here
        //     if we restore it

        try {
            jscompRunner.run();

            if (jscompRunner.hasErrors() || jscompRunner.exitCode != 0) {
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

        private final Compiler compiler;
        private Integer exitCode;

        InProcessJsCompRunner(String[] args, Compiler compiler, boolean exportTestFunctions) {
            super(args);
            this.compiler = compiler;
            this.exportTestFunctions = exportTestFunctions;
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

            return options;
        }
    }


}