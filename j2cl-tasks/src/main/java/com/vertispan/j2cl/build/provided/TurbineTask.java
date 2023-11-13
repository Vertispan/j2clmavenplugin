package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.SourceUtils;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.main.Main;
import com.google.turbine.options.TurbineOptions;
import com.vertispan.j2cl.build.task.*;

import javax.lang.model.SourceVersion;
import java.io.*;
import java.nio.file.*;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@AutoService(TaskFactory.class)
public class TurbineTask extends JavacTask {

    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");

    @Override
    public String getOutputType() {
        return OutputTypes.STRIPPED_BYTECODE_HEADERS;
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
        int version = SourceVersion.latestSupported().ordinal();
        if(version == 8) {
            return super.resolve(project, config);
        }

        // emits only stripped bytecode, so we're not worried about anything other than .java files to compile and .class on the classpath
        Input ownSources = input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES);

        List<File> extraClasspath = config.getExtraClasspath();

        List<Input> compileClasspath = scope(project.getDependencies(), Dependency.Scope.COMPILE).stream()
                .map(p -> input(p, OutputTypes.STRIPPED_BYTECODE_HEADERS))
                .map(input -> input.filter(JAVA_BYTECODE))
                .collect(Collectors.toUnmodifiableList());

        return context -> {

            Set<String> deps = Stream.concat(extraClasspath.stream().map(File::toString),
                            compileClasspath.stream().map(Input::getParentPaths).flatMap(Collection::stream)
                                    .map(p -> p.resolve("output.jar"))
                                    .map(Path::toString))
                    .collect(Collectors.toSet());

            File resultFolder = context.outputPath().toFile();
            File output = new File(resultFolder, "output.jar");

            List<String> sources = ownSources.getFilesAndHashes()
                    .stream()
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .map(SourceUtils.FileInfo::sourcePath)
                    .collect(Collectors.toUnmodifiableList());

            try {
                Main.Result result = Main.compile(
                        TurbineOptions.builder()
                                .setSources(ImmutableList.copyOf(sources))
                                .setOutput(output.toString())
                                .setClassPath(ImmutableList.copyOf(deps))
                                //TODO https://github.com/Vertispan/j2clmavenplugin/issues/181
                                //.setReducedClasspathMode(TurbineOptions.ReducedClasspathMode.JAVABUILDER_REDUCED)
                                .build());

                context.debug("turbine finished: " + result);
                extractJar(output, resultFolder.toPath(), context);
            } catch (TurbineError e) {
                // usually it means, it's an apt that can't be processed, log it
                context.info(e.getMessage());
            }
        };
    }

    public void extractJar(File zipFile, Path target, TaskContext context) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                if(zipEntry.getName().contains(ClassPathBinder.TRANSITIVE_PREFIX)) {
                    zipEntry = zis.getNextEntry();
                    continue;
                }
                boolean isDirectory = false;
                if (zipEntry.getName().endsWith(File.separator)) {
                    isDirectory = true;
                }
                Path newPath = target.resolve(zipEntry.getName());
                if (isDirectory) {
                    Files.createDirectories(newPath);
                } else {
                    if (newPath.getParent() != null) {
                        if (Files.notExists(newPath.getParent())) {
                            Files.createDirectories(newPath.getParent());
                        }
                    }
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        } catch (IOException e) {
            context.error(e);
            throw new IllegalStateException("Error while running turbine");
        }
    }

}
