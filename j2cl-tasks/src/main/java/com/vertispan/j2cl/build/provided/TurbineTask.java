package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.SourceUtils;
import com.google.turbine.main.Main;
import com.google.turbine.options.LanguageVersion;
import com.google.turbine.options.TurbineOptions;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Dependency;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;

@AutoService(TaskFactory.class)
public class TurbineTask extends TaskFactory {

    public static final PathMatcher JAVA_SOURCES = withSuffix(".java");

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

        List<File> extraClasspath = config.getExtraClasspath();

        Set<String> deps = Stream.concat(extraClasspath.stream().map(File::toString),
                        project.getDependencies().stream()
                                .map(Dependency::getJar)
                                .map(File::toString))
                .collect(Collectors.toSet());

        return context -> {

            File resultFolder = context.outputPath().toFile();
            File output = new File(resultFolder, "output.jar");

            List<String> sources = ownSources.getFilesAndHashes()
                    .stream()
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                    .map(SourceUtils.FileInfo::sourcePath)
                    .collect(Collectors.toList());

            Main.Result result =  Main.compile(
                    optionsWithBootclasspath()
                            .setSources(ImmutableList.copyOf(sources))
                            .setOutput(output.toString())
                            .setClassPath(ImmutableList.copyOf(deps))
                            .build());

            System.out.println("turbine finished: " + result);
            extractJar(output.toString(), resultFolder.toString());
        };
    }

    public void extractJar(String zipFilePath, String extractDirectory) {
        InputStream inputStream;
        try {
            Path filePath = Paths.get(zipFilePath);
            inputStream = Files.newInputStream(filePath);
            ArchiveStreamFactory archiveStreamFactory = new ArchiveStreamFactory();
            ArchiveInputStream archiveInputStream = archiveStreamFactory.createArchiveInputStream(ArchiveStreamFactory.ZIP, inputStream);
            ArchiveEntry archiveEntry ;
            while((archiveEntry = archiveInputStream.getNextEntry()) != null) {
                Path path = Paths.get(extractDirectory, archiveEntry.getName());
                File file = path.toFile();
                if(archiveEntry.isDirectory()) {
                    if(!file.isDirectory()) {
                        file.mkdirs();
                    }
                } else {
                    File parent = file.getParentFile();
                    if(!parent.isDirectory()) {
                        parent.mkdirs();
                    }
                    try (OutputStream outputStream = Files.newOutputStream(path)) {
                        IOUtils.copy(archiveInputStream, outputStream);
                    }
                }
            }
        } catch (IOException | ArchiveException e) {
            throw new RuntimeException(e);
        }
    }

    public static TurbineOptions.Builder optionsWithBootclasspath() {
        TurbineOptions.Builder options = TurbineOptions.builder();
        if (!BOOTCLASSPATH.isEmpty()) {
            options.setBootClassPath(
                    BOOTCLASSPATH.stream().map(Path::toString).collect(toImmutableList()));
        } else {
            options.setLanguageVersion(LanguageVersion.fromJavacopts(ImmutableList.of("--release", "8")));
        }
        return options;
    }

    private static final Splitter CLASS_PATH_SPLITTER =
            Splitter.on(File.pathSeparatorChar).omitEmptyStrings();

    public static final ImmutableList<Path> BOOTCLASSPATH =
            CLASS_PATH_SPLITTER
                    .splitToStream(Optional.ofNullable(System.getProperty("sun.boot.class.path")).orElse(""))
                    .map(Paths::get)
                    .filter(Files::exists)
                    .collect(toImmutableList());
}
