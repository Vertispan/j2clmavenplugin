/*
 * Copyright © 2022 j2cl-maven-plugin authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example;

import com.google.auto.common.AnnotationMirrors;
import com.google.auto.common.AnnotationValues;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class MyProcessor extends AbstractProcessor {
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton("com.example.MyAnnotation");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement annotationElt = processingEnv.getElementUtils().getTypeElement("com.example.MyAnnotation");
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(annotationElt);

        Filer filer = processingEnv.getFiler();
        elements.stream().filter(e -> e.getKind().isInterface()).forEach(type -> {
            //emit a new class for that type, with no-arg string methods
            try {
                ClassName origClassName = ClassName.get((TypeElement) type);
                TypeSpec.Builder builder = TypeSpec.classBuilder(origClassName.simpleName() + "_Impl")
                        .addModifiers(Modifier.PUBLIC)
                        .addSuperinterface(origClassName);
                for (Element enclosedElement : type.getEnclosedElements()) {
                    if (enclosedElement instanceof ExecutableElement) {
                        ExecutableElement method = (ExecutableElement) enclosedElement;

                        Optional<? extends AnnotationMirror> myAnnotation = enclosedElement.getAnnotationMirrors()
                                .stream()
                                .filter(annMirror -> annMirror.getAnnotationType().asElement().equals(annotationElt))
                                .findAny();
                        if (!myAnnotation.isPresent()) {
                            continue;
                        }
                        AnnotationValue value = AnnotationMirrors.getAnnotationValue(myAnnotation.get(), "value");
                        String path = AnnotationValues.getString(value);

                        CharSequence contents = getStringContentsOfPath(filer, path);
                        MethodSpec methodSpec = MethodSpec.methodBuilder(method.getSimpleName().toString())
                                .addModifiers(Modifier.PUBLIC)
                                .addAnnotation(Override.class)
                                .returns(String.class)
                                .addCode("return $S;", contents)
                                .build();

                        builder.addMethod(methodSpec);
                    }
                }
                JavaFile newFile = JavaFile.builder(origClassName.packageName(), builder.build()).build();
                newFile.writeTo(filer);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return true;
    }

    private CharSequence getStringContentsOfPath(Filer filer, String path) throws IOException {
        for (JavaFileManager.Location location : Arrays.asList(
                StandardLocation.SOURCE_PATH,
                StandardLocation.SOURCE_OUTPUT,
                StandardLocation.CLASS_PATH,
                StandardLocation.CLASS_OUTPUT,
                StandardLocation.ANNOTATION_PROCESSOR_PATH
        )) {
            try {
                FileObject resource = filer.getResource(location, "", path);
                if (resource != null && new File(resource.getName()).exists()) {
                    return resource.getCharContent(false);
                }
            } catch (IOException e) {
                //ignore, look in the next entry
            }
        }
        try (InputStream inputStream = getClass().getResourceAsStream("/" + path)) {
            if (inputStream != null) {
                final char[] buffer = new char[1024];
                final StringBuilder out = new StringBuilder();
                try (Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                    while (true) {
                        int rsz = in.read(buffer, 0, buffer.length);
                        if (rsz < 0)
                            break;
                        out.append(buffer, 0, rsz);
                    }
                }
                return out.toString();
            }
            throw new IllegalStateException("Failed to find resource " + path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource " + path, e);
        }
    }
}
