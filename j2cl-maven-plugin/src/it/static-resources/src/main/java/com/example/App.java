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
