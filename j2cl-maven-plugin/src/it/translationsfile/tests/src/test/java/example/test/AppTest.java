package example.test;

import com.google.j2cl.junit.apt.J2clTestInput;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

@J2clTestInput(AppTest.class)
public class AppTest {

    @Test
    public void value() {
        if(ValueHolder.testEscape()) {
            UnescapedAndEscapedCheck impl = new UnescapedAndEscapedCheck();
            // test unescaped values
            assertEquals("@TranslationKey example.test.AppTest !", impl.test(getClass().getCanonicalName()));
            assertEquals("TranslationService", impl.test2("TranslationService"));
            assertEquals("arg1arg2", impl.test3("arg1","arg2"));
            assertEquals("Tests run: 0, Failures: 2, Errors: 3, Skipped: 4, Time elapsed: 5 sec", impl.test4("0", "2", "3", "4", "5"));
            assertEquals("arg1 TranslationKey arg1 TranslationKey arg2 ", impl.test5("arg1 ", " arg1 ", " arg2 "));
            assertEquals("<br/>TranslationService<div id=\"this\">TranslationKeyTranslationService</div>", impl.test6("TranslationService", "TranslationService"));
            assertEquals("TranslationService&TranslationService", impl.test7("TranslationService", "TranslationService"));
            assertEquals("<div id=\"this\">!@#$%^*(((</div>TranslationService&TranslationService", impl.test8("TranslationService", "TranslationService"));
            assertEquals("<div id=\"this\">!@#$%^*(((</div>TranslationService&TranslationService@#$%^&*(<div>TranslationService</div>", impl.test9("TranslationService", "TranslationService", "TranslationService"));
            assertEquals("<div id=\"someId\">some content<br/><a href=\"#someRef\">TranslationService</a>,</div>", impl.test10("TranslationService"));
            assertEquals("<div id=\"WOW\">3TranslationService-!!!!!!!!!!!!!!!!!</div>", impl.test11("TranslationService"));
            assertEquals("<div id=\"div1\"><div id=\"div2\"><div id=\"div3\"/><div id=\"div4\"/></div></div>", impl.test12());
            assertEquals("<div>TranslationKey</div>", impl.test13());
            assertEquals("<div id=\"div1\"><div id=\"div2\"><div id=\"div3\">RRRRRRRTranslationService</div><div id=\"div4\"></div></div></div>", impl.test14("TranslationService"));
            assertEquals("7128890306670950348232162507662243061846645994402114603180927379544880168678174568758504050459062584", impl.test15());
            assertEquals("&<div id=\"my_widget\">@TranslationKey</div>", impl.test16("my_widget"));

            // test escaped values
            assertEquals("&lt;br/&gt;my_widget&lt;div id=&quot;this&quot;&gt;inner text my_widget&lt;/div&gt;", impl.test17("my_widget", "my_widget"));
            assertEquals("&lt;div id=&quot;_this&quot;&gt;!@#$%^*(((&lt;/div&gt;my_widget&amp;my_widget", impl.test18("my_widget", "my_widget"));
            assertEquals("&lt;div id=&quot;_this&quot;&gt;!@#$%^*(((&lt;/div&gt;my_widget&amp;qwerty@#$%^&amp;*(&lt;div&gt;asdfg&lt;/div&gt;", impl.test19("my_widget", "qwerty", "asdfg"));
            assertEquals("&lt;div id=&quot;_someId&quot;&gt;some content&lt;br/&gt;&lt;a href=&quot;#someRef&quot;&gt;my_widget&lt;/a&gt;,&lt;/div&gt;", impl.test20("my_widget"));
            assertEquals("&lt;div id=&quot;div_id&quot;&gt;3my_widget-!!!!!!!!!!!!!!!!!&lt;/div&gt;", impl.test21("my_widget"));
            assertEquals("&lt;div id=&quot;div21&quot;&gt;&lt;div id=&quot;div22&quot;&gt;&lt;div id=&quot;div23&quot;/&gt;&lt;div id=&quot;div14&quot;/&gt;&lt;/div&gt;&lt;/div&gt;", impl.test22());
            assertEquals("&lt;div&gt;inner content&lt;/div&gt;", impl.test23());
            assertEquals("&lt;div id=&quot;div21&quot;&gt;&lt;div id=&quot;div22&quot;&gt;&lt;div id=&quot;div23&quot;&gt;RRRRRRRmy_widget&gt;&lt;/div&gt;&lt;div id=&quot;div4&quot;/&gt;&lt;/div&gt;&lt;/div&gt;", impl.test24("my_widget"));

        } else {
            assertEquals(ValueHolder.getExpectedValue(), TranslationService.format());
        }
    }
}
