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
package example.test;

import jsinterop.annotations.JsMethod;

public class UnescapedAndEscapedCheck {

    @JsMethod
    public native String test2(String arg);
    @JsMethod
    public native String test(String arg);
    @JsMethod
    public native String test7(String arg,String arg1);
    @JsMethod
    public native String test11(String arg);
    @JsMethod
    public native String test6(String arg,String arg1);
    @JsMethod
    public native String test9(String arg,String arg1,String arg2);
    @JsMethod
    public native String test5(String arg,String arg1,String arg2);
    @JsMethod
    public native String test3(String arg,String arg1);
    @JsMethod
    public native String test8(String arg,String arg1);
    @JsMethod
    public native String test16(String arg);
    @JsMethod
    public native String test15();
    @JsMethod
    public native String test4(String arg,String arg1,String arg2,String arg3,String arg4);
    @JsMethod
    public native String test10(String arg);
    @JsMethod
    public native String test14(String arg);
    @JsMethod
    public native String test12();
    @JsMethod
    public native String test13();
    @JsMethod
    public native String test17(String var1, String var2);
    @JsMethod
    public native String test18(String var1, String var2);
    @JsMethod
    public native String test19(String var1, String var2, String var3);
    @JsMethod
    public native String test20(String var1);
    @JsMethod
    public native String test21(String var1);
    @JsMethod
    public native String test22();
    @JsMethod
    public native String test23();
    @JsMethod
    public native String test24(String var1);

}

