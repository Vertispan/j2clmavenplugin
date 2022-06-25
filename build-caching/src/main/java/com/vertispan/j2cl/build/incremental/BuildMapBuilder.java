/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vertispan.j2cl.build.incremental;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.SignatureAttribute;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a clone of LibraryInfoBuilder, with all pruning removed.
 * The idea is to first figure out the desired behaviour. It may be that
 * that logic is merged into LibraryInfoBuilder or a separate class is needed.
 * Once this reaches an agreed milestone, discussions can
 * start on changes needed to progress this further.
 */
public final class BuildMapBuilder {
    private final Path outputPath;

    private final String LINE_SEPARATOR = System.lineSeparator();

    public BuildMapBuilder(Path outputPath) {
        this.outputPath = outputPath;
    }

    public void addClass(CtClass clazz) throws NotFoundException {
        StringBuffer sb = new StringBuffer("- sources");
        sb.append(LINE_SEPARATOR);
        sb.append(clazz.getName());
        sb.append(":");
        sb.append(LINE_SEPARATOR);
        sb.append("- hierarchy");
        sb.append(LINE_SEPARATOR);

        CtClass superClass = clazz.getSuperclass();

        if (!superClass.getName().equals("java.lang.Object")) {
            sb.append(superClass.getName());
            sb.append(":");
            sb.append(LINE_SEPARATOR);
        }

        sb.append("- innerTypes");
        sb.append(LINE_SEPARATOR);

        Arrays.stream(clazz.getDeclaredClasses()).forEach(innerClass -> {
            sb.append(innerClass.getName());
            sb.append(LINE_SEPARATOR);
        });

        sb.append("- interfaces");
        sb.append(LINE_SEPARATOR);

        Arrays.stream(clazz.getInterfaces()).forEach(interfaceClass -> {
            sb.append(interfaceClass.getName());
            sb.append(LINE_SEPARATOR);
        });

        sb.append("- dependencies");
        sb.append(LINE_SEPARATOR);

        Set<String> types = new HashSet<>();

        clazz.getRefClasses()
                .forEach(
                        c -> maybeAddType(c, types));

        Arrays.stream(clazz.getFields())
                .forEach(
                        field -> {
                            try {
                                if(field.getGenericSignature() != null) {
                                    SignatureAttribute.Type typeSignature = SignatureAttribute.toTypeSignature(field.getGenericSignature());
                                    if(typeSignature instanceof javassist.bytecode.SignatureAttribute.ClassType) {
                                        javassist.bytecode.SignatureAttribute.ClassType classType =
                                                (javassist.bytecode.SignatureAttribute.ClassType) typeSignature;
                                        for (SignatureAttribute.TypeArgument typeArgument : classType.getTypeArguments()) {
                                            maybeAddType(typeArgument.toString(), types);
                                        }
                                    }
                                }
                                maybeAddType(field.getType().getName(), types);
                            } catch (NotFoundException e) {
                                throw new RuntimeException(e);
                            } catch (BadBytecode e) {
                                throw new RuntimeException(e);
                            }
                        });

        types.forEach(type -> {
            if (!clazz.getName().equals(type)) {
                sb.append(type);
                sb.append(LINE_SEPARATOR);
            }
        });


        String pkg = clazz.getPackageName().replaceAll("\\.", System.getProperty("file.separator"));
        File output = new File(outputPath.toFile(), pkg + "/" + clazz.getSimpleName() + ".build.map");

        Path path = Paths.get(output.toURI());
        byte[] strToBytes = sb.toString().getBytes();
        try {
            Files.write(path, strToBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    void maybeAddType(String clazz, Set<String> types) {
        if(!(clazz.startsWith("java."))) {
            types.add(clazz);
        }
    }
}
