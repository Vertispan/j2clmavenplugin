package net.cardosi.mojo.tools;

import com.google.j2cl.common.Problems;
import com.google.j2cl.frontend.FrontendUtils;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspilerOptions;

import java.io.File;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//TODO factor out the wiring to set this up for reuse
public class J2cl {

    private final J2clTranspilerOptions.Builder optionsBuilder;

    public J2cl(List<File> strippedClasspath, File bootstrap, File jsOutDir) {
        optionsBuilder = J2clTranspilerOptions.newBuilder()
                .setClasspaths(Stream.concat(Stream.of(bootstrap), strippedClasspath.stream())
                        .map(File::getAbsolutePath)
                        .collect(Collectors.toList())
                )
                .setOutput(jsOutDir.toPath())
                .setDeclareLegacyNamespace(true)//TODO parameterize these?
                .setEmitReadableLibraryInfo(false)
                .setEmitReadableSourceMap(false)
                .setGenerateKytheIndexingMetadata(false);
    }

    public boolean transpile(List<FrontendUtils.FileInfo> sourcesToCompile, List<FrontendUtils.FileInfo> nativeSources) {
        Problems problems = J2clTranspiler.transpile(
                optionsBuilder
                        .setSources(sourcesToCompile)
                        .setNativeSources(nativeSources)
                        .build());
//        if (problems.hasErrors() || problems.hasWarnings()) {
//            problems.getErrors().forEach(System.out::println);
//            problems.getWarnings().forEach(System.out::println);
//        } else {
//            problems.getInfoMessages().forEach(System.out::println);
//        }
        problems.getMessages().forEach(System.out::println);
        return !problems.hasErrors();
    }
}
