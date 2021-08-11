package j2clsample.nontest;

import jsinterop.annotations.JsType;
import jsinterop.base.JsArrayLike;

@JsType
public class MyType {

    public static String testMe() {
        final MyList list = new MyList();
        list.setLength(2);
        list.setAt(0, "Michael");
        list.setAt(1, "Dmitrii");
        String result = "";
        for (int i = 0; i < list.getLength(); i++) {
            result = result + list.getAt(i);
        }
        return result;
    }
}