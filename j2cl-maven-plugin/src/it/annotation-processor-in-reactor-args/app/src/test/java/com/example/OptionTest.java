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

import org.junit.Assert;
import org.junit.Test;

@J2clTestInput(OptionTest.class)
public class OptionTest {
    @Test
    public void resourceContents() {
        // This test verifies that the resource contents were correctly read at build time
        MyOption res = MyOption.INSTANCE;

        Assert.assertEquals("my-option", res.getOption());
    }
}
