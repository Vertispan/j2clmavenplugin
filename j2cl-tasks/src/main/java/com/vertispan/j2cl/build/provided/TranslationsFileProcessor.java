package com.vertispan.j2cl.build.provided;

import com.vertispan.j2cl.build.task.*;
import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
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

    Optional<File> getTranslationsFile(List<Input> inputs, TaskContext context);

    class TranslationsFileNotDefined implements TranslationsFileProcessor {
        private final boolean shouldWarn;

        public TranslationsFileNotDefined(boolean shouldWarn) {
            this.shouldWarn = shouldWarn;
        }

        @Override
        public Optional<File> getTranslationsFile(List<Input> inputs, TaskContext context) {
            if (shouldWarn) {
                context.log().warn("translationsFile only works in the ADVANCED optimization level, in other levels the default messages values will be used");
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
        public Optional<File> getTranslationsFile(List<Input> inputs, TaskContext context) {
            List<File> temp = inputs.stream()
                    .map(Input::getFilesAndHashes)
                    .flatMap(Collection::stream)
                    .map(CachedPath::getAbsolutePath)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            if (temp.isEmpty()) {
                context.log().warn("no .xtb files was found");
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

                HashMap<String, Set<NodeList>> suitableFiles = new HashMap<>();
                Set<String> locales = locales(locale);

                for (File xtb : temp) {
                    Document doc = db.parse(xtb);
                    doc.getDocumentElement().normalize();
                    NodeList translationbundleNode = doc.getElementsByTagName("translationbundle");

                    if (translationbundleNode.getLength() == 0) {
                        throw new RuntimeException(String.format("%s file has no translationbundle declaration", xtb));
                    }

                    String lang = translationbundleNode.item(0).getAttributes().getNamedItem("lang").getNodeValue();

                    if (locales.contains(lang)) {
                        if(!suitableFiles.containsKey(lang)) {
                            suitableFiles.put(lang, new HashSet<>());
                        }

                        suitableFiles.get(lang).add(translationbundleNode);
                    }
                }
                if(!suitableFiles.isEmpty()) {
                    return mergeFiles(suitableFiles, locales, context);
                }
            } catch (ParserConfigurationException | SAXException | IOException e) {
                context.log().error("Error while reading xtb files ", e);
                throw new RuntimeException(e);
            }
            context.log().warn("No matching locales for " + locale);
            return Optional.empty();
        }


        private Set<String> locales(String locale) {
            Set<String> result = new HashSet<>();
            result.add(locale);

            if (locale.contains("_")) {
                // according to spec, locale discaration can have 3 parts max
                if(locale.indexOf("_") != locale.lastIndexOf("_")) {
                    result.add(locale.substring(0, locale.lastIndexOf("_")));
                }
                result.add(locale.substring(0, locale.indexOf("_")));
            }
            return result;
        }

        private Optional<File> mergeFiles(HashMap<String, Set<NodeList>> suitableFiles, Set<String> locales, TaskContext context) {
            Map<String, Node> resultedCodeSet = new HashMap<>();
            // parent nodes first
            locales.stream().sorted((o1, o2) -> {
                if(o1.split("_").length > o2.split("_").length) {
                    return 1;
                }
                return -1;
            }).forEach(locale -> {
                suitableFiles.get(locale).forEach(node -> {
                    NodeList children = node.item(0).getChildNodes();

                    for (int i = 0; i < children.getLength(); i++) {
                        if (children.item(i).getNodeName().equals("translation")) {
                            String key = children.item(i).getAttributes().getNamedItem("id").getNodeValue();
                            // override parent keys if already exists
                            resultedCodeSet.put(key, children.item(i));
                        }
                    }
                });
            });

            Path folder = context.outputPath();
            File generated = folder.resolve("generated_messages.xtb").toFile();
            generateAndWriteXTB(resultedCodeSet, generated);
            return Optional.of(generated);
        }

        private void generateAndWriteXTB(Map<String, Node> resultedCodeSet, File generated) {
            StringBuffer sb = new StringBuffer();

            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            sb.append(System.lineSeparator());
            sb.append("<!DOCTYPE translationbundle SYSTEM \"translationbundle.dtd\">");
            sb.append(System.lineSeparator());

            sb.append("<translationbundle lang=\"" + locale + "\">");
            sb.append(System.lineSeparator());

            resultedCodeSet.values().forEach(node -> {
                sb.append("  <translation id=\"");
                sb.append(node.getAttributes().getNamedItem("id").getNodeValue());
                sb.append("\" key=\"");
                sb.append(node.getAttributes().getNamedItem("key").getNodeValue());
                sb.append("\">");

                // we have to process placeholders here
                for (int i = 0; i < node.getChildNodes().getLength(); i++) {
                    Node innerTextNode = node.getChildNodes().item(i);
                    if(innerTextNode.getNodeType() == Node.TEXT_NODE) {
                        sb.append(innerTextNode.getTextContent());
                    } else if(innerTextNode.getNodeType() == Node.ELEMENT_NODE && innerTextNode.getNodeName().equals("ph")) {
                        sb.append("<ph name=\"");
                        sb.append(innerTextNode.getAttributes().getNamedItem("name").getNodeValue());
                        sb.append("\"/>");
                    }
                }
                sb.append("</translation>");
                sb.append(System.lineSeparator());
            });

            sb.append("</translationbundle>");
            sb.append(System.lineSeparator());

            try {
                FileUtils.writeStringToFile(generated, sb.toString(), Charset.forName("UTF-8"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
