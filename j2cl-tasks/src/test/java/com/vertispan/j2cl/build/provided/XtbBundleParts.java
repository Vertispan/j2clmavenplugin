package com.vertispan.j2cl.build.provided;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class XtbBundleParts {

    TranslationsFileProcessor.ProjectLookup projectLookup = new TranslationsFileProcessor.ProjectLookup("zr");

    @Test
    public void testEscape() {
        String content = "Au revoir <ph name=\"arg\" /> !";
        List<String> parts = projectLookup.parse(content);

        assertEquals(3, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape2() {
        String content = "<ph name=\"arg\" />";
        List<String> parts = projectLookup.parse(content);

        assertEquals(1, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape3() {
        String content = "<ph name=\"arg\" ></ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(1, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape4() {
        String content = "<ph name=\"arg\" >QWERTY</ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(1, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape5() {
        String content = "<ph name=\"arg\" >QWERTY</ph><ph name=\"arg\" >QWERTY</ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(2, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape6() {
        String content = "<ph name=\"arg\" >QWERTY</ph><ph name=\"arg\" />";
        List<String> parts = projectLookup.parse(content);

        assertEquals(2, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape7() {
        String content = "<ph name=\"arg\" /><ph name=\"arg\" >QWERTY</ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(2, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape8() {
        String content = "<ph name=\"arg\" />QWERTY<ph name=\"arg\" >QWERTY</ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(3, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape9() {
        String content = "QWERTY<ph name=\"arg\" />QWERTY<ph name=\"arg\" >QWERTY</ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(4, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape10() {
        String content = "<div id='WOW'><ph name=\"arg\" /></div>";
        String escaped = "&amp;lt;div id=&amp;apos;WOW&amp;apos;&amp;gt;<ph name=\"arg\" />&amp;lt;/div&amp;gt;";
        List<String> parts = projectLookup.parse(content);

        assertEquals(3, parts.size());
        assertEquals(escaped, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape11() {
        String content = "<div id='WOW'><ph name=\"arg\" >QWERTY</ph></div>";
        String escaped = "&amp;lt;div id=&amp;apos;WOW&amp;apos;&amp;gt;<ph name=\"arg\" >QWERTY</ph>&amp;lt;/div&amp;gt;";
        List<String> parts = projectLookup.parse(content);

        parts.forEach(System.out::println);

        assertEquals(3, parts.size());
        System.out.println("??? " + parts.stream().collect(Collectors.joining("")));
        assertEquals(escaped, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape12() {
        String content = "<div id=\"div1\"><div id=\"div2\"><div id=\"div3\"></div><div id=\"div4\"></div></div></div>";
        String escaped = "&amp;lt;div id=&amp;quot;div1&amp;quot;&amp;gt;&amp;lt;div id=&amp;quot;div2&amp;quot;&amp;gt;&amp;lt;div id=&amp;quot;div3&amp;quot;&amp;gt;&amp;lt;/div&amp;gt;&amp;lt;div id=&amp;quot;div4&amp;quot;&amp;gt;&amp;lt;/div&amp;gt;&amp;lt;/div&amp;gt;&amp;lt;/div&amp;gt;";
        List<String> parts = projectLookup.parse(content);

        assertEquals(1, parts.size());
        assertEquals(escaped, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape13() {
        String content = "<div>WOW</div>";
        String escaped = "&amp;lt;div&amp;gt;WOW&amp;lt;/div&amp;gt;";
        List<String> parts = projectLookup.parse(content);

        assertEquals(1, parts.size());
        assertEquals(escaped, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape14() {
        String content = "<div id=\"div1\"><ph name=\"arg\" >QWERTY</ph><div id=\"div2\"><div id=\"div3\">RRRRRRR<ph name=\"arg\" /></div><ph name=\"arg\" /><div id=\"div4\"></div></div></div><ph name=\"arg\" >QWERTY</ph>";
        String escaped = "&amp;lt;div id=&amp;quot;div1&amp;quot;&amp;gt;<ph name=\"arg\" >QWERTY</ph>&amp;lt;div id=&amp;quot;div2&amp;quot;&amp;gt;&amp;lt;div id=&amp;quot;div3&amp;quot;&amp;gt;RRRRRRR<ph name=\"arg\" />&amp;lt;/div&amp;gt;<ph name=\"arg\" />&amp;lt;div id=&amp;quot;div4&amp;quot;&amp;gt;&amp;lt;/div&amp;gt;&amp;lt;/div&amp;gt;&amp;lt;/div&amp;gt;<ph name=\"arg\" >QWERTY</ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(8, parts.size());
        assertEquals(escaped, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape15() {
        String content = "RRRRRRR<ph name=\"arg\" />";
        List<String> parts = projectLookup.parse(content);

        parts.forEach(System.out::println);

        assertEquals(2, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape16() {
        String content = "RRRRRRR<ph name=\"arg\" ></ph>";
        List<String> parts = projectLookup.parse(content);

        assertEquals(2, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

    @Test
    public void testEscape17() {
        String content = "RRRRRRR<ph name=\"arg\" />";
        List<String> parts = projectLookup.parse(content);

        assertEquals(2, parts.size());
        assertEquals(content, parts.stream().collect(Collectors.joining("")));
    }

}

