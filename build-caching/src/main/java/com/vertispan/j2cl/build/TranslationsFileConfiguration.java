package com.vertispan.j2cl.build;

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

public class TranslationsFileConfiguration {

    private boolean auto;

    private File file;

    private Config config;
    List<com.vertispan.j2cl.build.task.Input> jsSources;

    public TranslationsFileConfiguration(Config config, File file) {
        this.file = file;
        this.config = config;
    }

    public TranslationsFileConfiguration(Config config, boolean auto) {
        this.auto = auto;
        this.config = config;
    }

    public Optional<File> getFile() {
        String locale = config.getDefines().get("goog.LOCALE");

        if(locale == null) {
            System.out.println("No goog.LOCALE set, skipping .xtb lookup");
            return Optional.empty();
        }


        if (auto) {
            List<File> temp = jsSources.stream()
                    .map(Input::getFilesAndHashes)
                    .flatMap(Collection::stream)
                    .map(CachedPath::getAbsolutePath)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            if(temp.isEmpty()) {
                System.out.println("no .xtb files was found");
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

                    if(translationbundleNode.getLength() == 0) {
                        throw new RuntimeException(String.format("%s file has no translationbundle declaration", xtb));
                    }

                    String lang = translationbundleNode.item(0).getAttributes().getNamedItem("lang").getNodeValue();

                    if(locale.equals(lang)) {
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
        }
        return Optional.ofNullable(file);
    }

    public void setProjectInputs(List<Input> collect) {
        jsSources = collect;
    }
}
