/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
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

package net.cardosi.mojo;

import java.io.File;
import java.io.IOException;

import io.takari.maven.testing.TestMavenRuntime;
import io.takari.maven.testing.TestResources;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static io.takari.maven.testing.TestMavenRuntime.newParameter;
import static io.takari.maven.testing.TestResources.assertFilesPresent;

public class CleanMojoTest extends AbstractMojoTest {

    @Rule
    public final TestResources resources = new TestResources();

    @Rule
    public final TestMavenRuntime maven = new TestMavenRuntime();

    @Before
    public void setup() throws IOException {
        setup(resources.getBasedir("clean"));
    }

    @Test
    public void test() throws Exception {
        String[] dirsToTest = {intermediateJsPath, generatedClassesDir, outputJsPathDir, classesDir, jsZipCacheDir, outputDirectory};
        assertDirectoriesPresent(target, webappdir);
        assertDirectoriesPresent(dirsToTest);
        for (String dir : dirsToTest) {
            assertFilesPresent(new File(dir),"/empty.txt");
        }
        maven.executeMojo(baseDirFile, "clean",
                          newParameter("intermediateJsPath", intermediateJsPath),
                          newParameter("generatedClassesDir", generatedClassesDir),
                          newParameter("outputJsPathDir", outputJsPathDir),
                          newParameter("classesDir", classesDir),
                          newParameter("jsZipCacheDir", jsZipCacheDir),
                          newParameter("outputDirectory", outputDirectory)
        );
        assertDirectoriesNotPresent(dirsToTest);
        assertDirectoriesPresent(target, webappdir);
    }
}