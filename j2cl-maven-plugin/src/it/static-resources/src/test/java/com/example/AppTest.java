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

import com.google.j2cl.junit.apt.J2clTestInput;

import elemental2.promise.Promise;

import org.junit.Assert;
import org.junit.Test;

/**
 * Test has to be run by hand, htmlunit doesn't support fetch:
 * https://github.com/HtmlUnit/htmlunit/issues/78
 */
@J2clTestInput(AppTest.class)
public class AppTest {
    @Test(timeout = 1000)
    public Promise<?> testPublicFile() {
        return App.getData("publicfile.js");
    }
    @Test(timeout = 1000)
    public Promise<?> testMetaInfFile() {
        return App.getData("metainfresourcesfile.js");
    }
    @Test(timeout = 1000)
    public Promise<?> testIgnoredFile() {
        // failure expected, we handle that in the .then() so that the test will only see success
        return App.getData("ignoredfile.js").then(text -> Promise.reject("failure expected"), fail -> {
            if ("404".equals(fail)) {
                return Promise.resolve("Succcess, saw 404");
            }
            return Promise.reject("Expected 404, saw " + fail);
        });
    }
}
