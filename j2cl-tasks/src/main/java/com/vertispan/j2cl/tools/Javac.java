package com.vertispan.j2cl.tools;

import com.google.j2cl.common.SourceUtils.FileInfo;
import com.vertispan.j2cl.build.task.BuildLog;

import javax.lang.model.SourceVersion;
import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs javac. Set this up with the appropriate classpath, directory for generated sources to be written,
 * and directory for bytecode to be written, and can be requested to preCompile any .java file where the
 * dependencies are appropriately already available.
 *
 * The classesDirFile generally should be in the classpath list.
 *
 * Note that incoming sources should already be pre-processed, and while it should be safe to directly
 * j2cl the generated classes, it may be necessary to pre-process them before passing them to j2cl.
 */
public class Javac {

    private final BuildLog log;
    List<String> javacOptions;
    JavaCompiler compiler;
    StandardJavaFileManager fileManager;
    private DiagnosticCollector<JavaFileObject> listener;

    public Javac(BuildLog log, File generatedClassesPath, List<File> sourcePaths, List<File> classpath, File classesDirFile, File bootstrap, Set<String> processors) throws IOException {
        this.log = log;
//        for (File file : classpath) {
//            System.out.println(file.getAbsolutePath() + " " + file.exists() + " " + file.isDirectory());
//        }
        javacOptions = new ArrayList<>(Arrays.asList("-encoding", "utf8", "-implicit:none", "-bootclasspath", bootstrap.toString()));
        if (generatedClassesPath == null) {
            javacOptions.add("-proc:none");
        }
        if (SourceVersion.latestSupported().compareTo(SourceVersion.RELEASE_8) > 0) {
            //java 9+
            javacOptions.add("--release=8");
        }
        if (!processors.isEmpty()) {
            javacOptions.add("-processor");
            javacOptions.add(String.join(",", processors));
        }

        compiler = ToolProvider.getSystemJavaCompiler();
        listener = new DiagnosticCollector<>();
        fileManager = compiler.getStandardFileManager(listener, null, null);
        fileManager.setLocation(StandardLocation.SOURCE_PATH, sourcePaths);
        if (generatedClassesPath != null) {
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesPath));
        }
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));
    }

    public boolean compile(List<FileInfo> modifiedJavaFiles) {
        // preCompile java files with javac into classesDir
        Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(
                modifiedJavaFiles.stream()
                        .map(FileInfo::sourcePath)
                        .collect(Collectors.toUnmodifiableList())
        );
        //TODO pass-non null for "classes" to properly kick apt?
        //TODO consider a different classpath for this tasks, so as to not interfere with everything else?

        CompilationTask task = compiler.getTask(null, fileManager, listener, javacOptions, null, modifiedFileObjects);

        try {
            return task.call();
        } finally {
            listener.getDiagnostics().forEach(d -> {
                String messageToLog = d.getMessage(Locale.getDefault());
                JavaFileObject source = d.getSource();

                if (source != null) {
                    String longFileName = source.toUri().getPath();
                    String prefix = longFileName + ((d.getLineNumber() > 0) ? ":" + d.getLineNumber() : "") + " ";
                    messageToLog = prefix + messageToLog;
                }
                switch (d.getKind()) {
                    case ERROR:
                        log.error(messageToLog);
                        break;
                    case WARNING:
                    case MANDATORY_WARNING:
                        log.warn(messageToLog);
                        break;
                    case NOTE:
                        log.info(messageToLog);
                        break;
                    case OTHER:
                        log.debug(messageToLog);
                        break;
                }
            });
        }
    }
}
