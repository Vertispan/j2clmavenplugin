package user.defined.externs;

import jsinterop.annotations.JsType;

//@Es6Module("js/module/SampleJsModule.js")//hypothetical
@JsType(namespace = "js.module", isNative = true)
public class SampleJsModule {
    public static native String helloWorld();
}