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
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
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

                Map<String, String> options = processingEnv.getOptions();
                CharSequence contents = options.getOrDefault("optionName", "default");
                MethodSpec methodSpec = MethodSpec.methodBuilder("getOption")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(String.class)
                        .addCode("return $S;", contents)
                        .build();

                builder.addMethod(methodSpec);
                JavaFile newFile = JavaFile.builder(origClassName.packageName(), builder.build()).build();
                newFile.writeTo(filer);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return true;
    }

}
