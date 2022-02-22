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
