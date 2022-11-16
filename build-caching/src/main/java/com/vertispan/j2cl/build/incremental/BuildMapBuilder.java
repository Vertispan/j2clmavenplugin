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

import com.google.common.hash.HashCode;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.SignatureAttribute;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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

    public void addClass(CtClass clazz, Path javaFilePath) throws NotFoundException {
        JavaFileHashReader javaFileHashReader = new JavaFileHashReader(javaFilePath);

        StringBuffer sb = new StringBuffer("- sources");
        sb.append(LINE_SEPARATOR);
        sb.append(clazz.getName());
        sb.append(":");
        sb.append(LINE_SEPARATOR);
        sb.append("- hierarchy");
        sb.append(LINE_SEPARATOR);

        CtClass superClass = clazz.getSuperclass();

        if (superClass != null) {
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

        clazz.getRefClasses()
                .stream()
                .filter(ref -> maybeAddType.test(clazz.getName(), ref))
                .forEach(
                        ref -> {
                            sb.append(ref);
                            sb.append(LINE_SEPARATOR);
                        });


        sb.append("- hash");
        sb.append(LINE_SEPARATOR);
        sb.append(javaFileHashReader.hash());
        sb.append(LINE_SEPARATOR);

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

    private BiPredicate<String, String> maybeAddType = (clazz, ref) -> !(ref.startsWith("java.") || clazz.equals(ref));

}
