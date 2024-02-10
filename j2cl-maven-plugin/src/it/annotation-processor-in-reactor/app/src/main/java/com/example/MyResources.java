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

@MyAnnotation
public interface MyResources {
    public static final MyResources INSTANCE = new MyResources_Impl();

    @MyAnnotation("res-in-root-dir.txt")
    String resourceInRoot();
    @MyAnnotation("com/example/res-in-package.txt")
    String resourceInPackage();

    @MyAnnotation("res-in-java-default-package.txt")
    String resourceInJavaSourceRoot();

    @MyAnnotation("com/example/res-in-java-nested-package.txt")
    String resourceInJavaPackage();
}
