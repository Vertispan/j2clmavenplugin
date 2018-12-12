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

import static io.takari.maven.testing.TestResources.assertFilesPresent;

public class BuildMojoTest extends AbstractMojoTest {

    @Rule
    public final TestResources resources = new TestResources();

    @Rule
    public final TestMavenRuntime maven = new TestMavenRuntime();

    @Before
    public void setup() throws IOException {
        setup(resources.getBasedir("build"));
    }

    @Test
    public void test() throws Exception {
        assertDirectoriesPresent(target, webappdir,webappLibDir, sourceDir);
        maven.executeMojo(baseDirFile, "build");
        String[] expectedFiles = {"jre.jar", "gwt-internal-annotations.jar", "bootstrap.js.zip", "jre.js.zip"};
        File webappLibDirFile = new File(webappLibDir);
        assertFilesPresent(webappLibDirFile, expectedFiles);
    }
}