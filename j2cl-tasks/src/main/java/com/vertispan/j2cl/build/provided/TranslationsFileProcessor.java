package com.vertispan.j2cl.build.provided;

import com.vertispan.j2cl.build.task.BuildLog;
import com.vertispan.j2cl.build.task.CachedPath;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Input;
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

public interface TranslationsFileProcessor {
    public static TranslationsFileProcessor get(Config config) {
        File file = config.getFile("translationsFile.file");
        boolean auto = Boolean.parseBoolean(config.getString("translationsFile.auto"));

        if ((auto || file != null) && !config.getCompilationLevel().equals("ADVANCED")) {
            return new TranslationsFileNotDefined(true);
        }
        if (file != null) {
            return (inputs, log) -> Optional.of(file);
        } else if (auto) {
            return new ProjectLookup(config);
        }
        return new TranslationsFileNotDefined(false);
    }

    Optional<File> getTranslationsFile(List<Input> inputs, BuildLog log);

    class TranslationsFileNotDefined implements TranslationsFileProcessor {
        private final boolean shouldWarn;

        public TranslationsFileNotDefined(boolean shouldWarn) {
            this.shouldWarn = shouldWarn;
        }

        @Override
        public Optional<File> getTranslationsFile(List<Input> inputs, BuildLog log) {
            if (shouldWarn) {
                log.warn("translationsFile only works in the ADVANCED optimization level, in other levels the default messages values will be used");
            }

            return Optional.empty();
        }
    }

    class ProjectLookup implements TranslationsFileProcessor {
        private final String locale;

        public ProjectLookup(Config config) {
            locale = config.getString("defines.goog.LOCALE");
        }

        @Override
        public Optional<File> getTranslationsFile(List<Input> inputs, BuildLog log) {
            List<File> temp = inputs.stream()
                    .map(Input::getFilesAndHashes)
                    .flatMap(Collection::stream)
                    .map(CachedPath::getAbsolutePath)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            if (temp.isEmpty()) {
                log.warn("no .xtb files was found");
            }

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
            } catch (ParserConfigurationException | SAXException | IOException e) {
                log.error("Error while reading xtb files ", e);
                throw new RuntimeException(e);
            }
            log.warn("No matching locales for " + locale);
            return Optional.empty();
        }

    }
}
