package net.cardosi.mojo.tools;

import com.google.j2cl.common.FrontendUtils.FileInfo;

import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    List<String> javacOptions;
    JavaCompiler compiler;
    StandardJavaFileManager fileManager;

    public Javac(File generatedClassesPath, List<File> classpath, File classesDirFile, File bootstrap) throws IOException {
//        for (File file : classpath) {
//            System.out.println(file.getAbsolutePath() + " " + file.exists() + " " + file.isDirectory());
//        }
        if (generatedClassesPath == null) {
            javacOptions = Arrays.asList("-proc:none", "-encoding", "utf8", "-implicit:none", "-bootclasspath", bootstrap.toString());

        } else {
            javacOptions = Arrays.asList("-encoding", "utf8", "-implicit:none", "-bootclasspath", bootstrap.toString());

        }
        compiler = ToolProvider.getSystemJavaCompiler();
        fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
        if (generatedClassesPath != null) {
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesPath));
        }
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));
    }

    public boolean compile(List<FileInfo> modifiedJavaFiles) {
        // preCompile java files with javac into classesDir
        Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(modifiedJavaFiles.stream().map(FileInfo::sourcePath).collect(Collectors.toList()));
        //TODO pass-non null for "classes" to properly kick apt?
        //TODO consider a different classpath for this tasks, so as to not interfere with everything else?

        CompilationTask task = compiler.getTask(null, fileManager, null, javacOptions, null, modifiedFileObjects);

        return task.call();
    }
}
