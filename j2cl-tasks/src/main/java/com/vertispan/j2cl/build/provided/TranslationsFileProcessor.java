package com.vertispan.j2cl.build.provided;

import com.vertispan.j2cl.build.task.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class TranslationsFileProcessor {

    private final Config config;

    private XTBLookup lookup = new TranslationsFileNotDefined();

    private List<Input> jsSources;

    public TranslationsFileProcessor(Config config) {
        this.config = config;
        if (!config.getTranslationsFile().isEmpty()) {
            if (!config.getCompilationLevel().equals("ADVANCED")) {
                //Do we have logger ?
                System.out.println("translationsFile only works in the ADVANCED optimization level, in other levels the default messages values will be used");
            } else {
                if (config.getTranslationsFile().containsKey("file")) {
                    //translation file explicitly defined
                    lookup = new ExplicitlyDefined();
                } else if (config.getTranslationsFile().containsKey("auto") && config.getTranslationsFile().get("auto").equals("true")) {
                    // we have to perform Project wide lookup
                    lookup = new ProjectLookup();
                }
            }
        }
    }

    public Optional<File> getTranslationsFile() {
        return lookup.getFile();
    }

    public void setProjectInputs(List<Input> collect) {
        this.jsSources = collect;
    }

    private interface XTBLookup {

        Optional<File> getFile();
    }

    private class TranslationsFileNotDefined implements XTBLookup {

        @Override
        public Optional<File> getFile() {
            return Optional.empty();
        }
    }

    private class ExplicitlyDefined implements XTBLookup {

        @Override
        public Optional<File> getFile() {
            System.out.println("getFile " + config.getTranslationsFile().get("file"));

            return Optional.of((File) config.getTranslationsFile().get("file"));
        }
    }

    private class ProjectLookup implements XTBLookup {

        @Override
        public Optional<File> getFile() {
            List<File> temp = jsSources.stream()
                    .map(Input::getFilesAndHashes)
                    .flatMap(Collection::stream)
                    .map(CachedPath::getAbsolutePath)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            if (temp.isEmpty()) {
                System.out.println("no .xtb files was found");
            }

            String locale = config.getDefines().get("goog.LOCALE");

            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

                dbf.setNamespaceAware(false);
                dbf.setValidating(false);
                dbf.setFeature("http://xml.org/sax/features/namespaces", false);
                dbf.setFeature("http://xml.org/sax/features/validation", false);
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

                DocumentBuilder db = dbf.newDocumentBuilder();

                for (File xtb : temp) {
                    Document doc = db.parse(xtb);
                    doc.getDocumentElement().normalize();
                    NodeList translationbundleNode = doc.getElementsByTagName("translationbundle");

                    if (translationbundleNode.getLength() == 0) {
                        throw new RuntimeException(String.format("%s file has no translationbundle declaration", xtb));
                    }

                    String lang = translationbundleNode.item(0).getAttributes().getNamedItem("lang").getNodeValue();

                    if (locale.equals(lang)) {
                        return Optional.of(xtb);
                    }
                }
            } catch (ParserConfigurationException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (SAXException e) {
                throw new RuntimeException(e);
            }
            return Optional.empty();
        }

    }
}
