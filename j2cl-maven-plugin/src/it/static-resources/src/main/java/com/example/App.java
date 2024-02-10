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

import elemental2.dom.DomGlobal;
import elemental2.dom.Response;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.annotations.JsType;

@JsType
public class App {
    public static void main() {
        getData("publicfile.js").then(data -> alert(data), err -> alert("fail!"));
        getData("metainfresourcesfile.js").then(data -> alert(data), err -> alert("fail!"));
        getData("ignoredfile.js").then(data -> alert("fail!"), err -> alert("success, file missing"));
    }
    private static Promise<Object> alert(Object data) {
        DomGlobal.alert(data);
        return Promise.resolve(data);
    }
    public static Promise<String> getData(String path) {
        return DomGlobal.fetch(path)
                .then(response -> {
                    if (response.ok) {
                        return response.text();
                    }
                    return Promise.reject(response.status + "");
                });
    }
}
