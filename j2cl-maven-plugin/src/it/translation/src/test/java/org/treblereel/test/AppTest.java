package org.treblereel.test;

import com.google.j2cl.junit.apt.J2clTestInput;
import elemental2.dom.DomGlobal;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

@J2clTestInput(AppTest.class)
public class AppTest {

    @Test
    public void value() {
        DomGlobal.console.log("??? " + TranslationService.format());
        assertEquals(ValueHolder.getExpectedValue(), TranslationService.format());
    }
}
