package com.vertispan.j2cl.build.incremental;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class JavaFileHashReader {

    private final Path path;

    public JavaFileHashReader(Path path) {
        this.path = path;
    }

    public String hash() {
        try(InputStream in = Files.newInputStream(path)) {
            CompilationUnit cu = new JavaParser().parse(in).getResult().get();
            HashBuilder builder = new HashBuilder();
            cu.findAll(FieldDeclaration.class).stream()
                    .filter(field -> !field.getModifiers().contains(Modifier.privateModifier()))
                    .forEach(field -> field.getVariables().forEach(variable -> {
                        builder.addField(variable.getName().toString(), variable.getType().toString());
                    }));

            cu.findAll(MethodDeclaration.class).stream()
                    .filter(method -> !method.getModifiers().contains(Modifier.privateModifier()))
                    .forEach(method -> {
                        String params = method.getParameters().stream()
                                .map(param -> param.getType().toString() + " " + param.getName())
                                .collect(Collectors.joining(", "));
                        builder.addMethod(method.getName().toString(), method.getType().toString(), params);
                    });

            return builder.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
