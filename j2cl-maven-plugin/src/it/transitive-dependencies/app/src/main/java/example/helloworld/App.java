package example.helloworld;

import example.lib1.Class1;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType
public class App {
    public void onModuleLoad() {
        log(new Class1().toString());
    }

    @JsMethod(name = "console.log", namespace = JsPackage.GLOBAL)
    private static native void log(String msg);
}