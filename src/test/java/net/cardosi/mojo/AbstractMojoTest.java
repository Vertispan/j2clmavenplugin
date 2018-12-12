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

import org.junit.Assert;

public class AbstractMojoTest {

    protected File baseDirFile;
    protected String baseDir;
    protected String target;
    protected String webappdir;
    protected String intermediateJsPath;
    protected String generatedClassesDir;
    protected String outputJsPathDir;
    protected String classesDir;
    protected String sourceDir;
    protected String jsZipCacheDir;
    protected String outputDirectory;

    public void assertDirectoriesPresent(String... paths) {
        if (paths == null || paths.length <= 0) {
            throw new IllegalArgumentException();
        }
        if (paths.length == 1) {
            Assert.assertTrue(paths[0] + " PRESENT", new File(paths[0]).isDirectory());
        } else {
            StringBuilder expected = new StringBuilder();
            StringBuilder actual = new StringBuilder();
            for (String path : paths) {
                expected.append(path).append("\n");
                if (!new File(path).isDirectory()) {
                    actual.append("NOT PRESENT ");
                }
                actual.append(path).append("\n");
            }
            Assert.assertEquals(expected.toString(), actual.toString());
        }
    }

    public void assertDirectoriesNotPresent(String... paths) {
        if (paths == null || paths.length <= 0) {
            throw new IllegalArgumentException();
        }
        if (paths.length == 1) {
            Assert.assertFalse(paths[0] + " NOT PRESENT", new File(paths[0]).isDirectory());
        } else {
            StringBuilder expected = new StringBuilder();
            StringBuilder actual = new StringBuilder();
            for (String path : paths) {
                expected.append(path).append("\n");
                if (new File(path).isDirectory()) {
                    actual.append("PRESENT ");
                }
                actual.append(path).append("\n");
            }
            Assert.assertEquals(expected.toString(), actual.toString());
        }
    }

    protected void setup(File baseDirFile) {
        this.baseDirFile = baseDirFile;
        baseDir = baseDirFile.getAbsolutePath();
        target = baseDir + "/test-target";
        webappdir = target + "/webapps";
        intermediateJsPath = target + "/js-sources";
        generatedClassesDir = target + "/gen-classes";
        outputJsPathDir = webappdir + "/js";
        classesDir = target + "/gen-bytecode";
        sourceDir = baseDir + "/src/main/java";
        jsZipCacheDir = baseDir + "/jsZipCache";
        outputDirectory = webappdir + "/WEB-INF/lib";
    }
}
