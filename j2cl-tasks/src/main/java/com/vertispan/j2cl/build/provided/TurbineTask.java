package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;
import com.vertispan.j2cl.tools.Javac;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class TurbineTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");
    public static final PathMatcher JAVA_BYTECODE = withSuffix(".class");

    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE_HEADERS;
    }

    @Override
    public String getTaskName() {
        return "turbine";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // emits only stripped bytecode, so we're not worried about anything other than .java files to compile and .class on the classpath
        Input ownSources = input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES);

        List<Input> classpathHeaders = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_BYTECODE_HEADERS))
                // we only want bytecode _changes_, but we'll use the whole dir
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toList());

        File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = config.getExtraClasspath();
        return context -> {
            if (ownSources.getFilesAndHashes().isEmpty()) {
                return;// no work to do
            }

            List<File> classpathDirs = Stream.concat(classpathHeaders.stream().map(Input::getParentPaths).flatMap(Collection::stream).map(Path::toFile),
                    extraClasspath.stream()).collect(Collectors.toList());

            List<File> sourcePaths = ownSources.getParentPaths().stream().map(Path::toFile).collect(Collectors.toList());
//            Javac javac = new Javac(context, null, sourcePaths, classpathDirs, context.outputPath().toFile(), bootstrapClasspath);

            // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
            //      automatically relativized?
            List<SourceUtils.FileInfo> sources = ownSources.getFilesAndHashes()
                    .stream()
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .collect(Collectors.toList());

//            javac.compile(sources);
        };
    }
}
