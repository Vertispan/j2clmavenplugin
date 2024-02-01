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
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
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
                context.warn("translationsFile only works in the ADVANCED optimization level, in other levels the default messages values will be used");
            }

            return Optional.empty();
        }
    }

    class ProjectLookup implements TranslationsFileProcessor {
        private final String locale;

        final static private String ph_open = "<ph ";
        final static private String ph_close = "</ph>";
        final static private String ph_self_close = "/>";

        ProjectLookup(Config config) {
            this(config.getString("defines.goog.LOCALE"));
        }

        ProjectLookup(String locale) {
            this.locale = locale;
        }

        @Override
        public Optional<File> getTranslationsFile(List<Input> inputs, TaskContext context) {
            List<File> xtbFiles = inputs.stream()
                    .map(Input::getFilesAndHashes)
                    .flatMap(Collection::stream)
                    .map(CachedPath::getAbsolutePath)
                    .map(Path::toFile)
                    .collect(Collectors.toUnmodifiableList());

            if (xtbFiles.isEmpty()) {
                context.warn("no .xtb files was found");
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

                for (File xtb : xtbFiles) {
                    Document doc = db.parse(xtb);
                    doc.getDocumentElement().normalize();
                    NodeList translationbundleNode = doc.getElementsByTagName("translationbundle");

                    if (translationbundleNode.getLength() == 0) {
                        throw new RuntimeException(String.format("%s file has no translationbundle declaration", xtb));
                    }

                    String lang = translationbundleNode.item(0).getAttributes().getNamedItem("lang").getNodeValue();

                    if (locales.contains(lang)) {
                        suitableFiles.computeIfAbsent(lang, k -> new HashSet<>())
                                     .add(translationbundleNode);
                    }
                }
                if(!suitableFiles.isEmpty()) {
                    return Optional.of(mergeFiles(suitableFiles, locales, context));
                }
            } catch (ParserConfigurationException | SAXException | IOException e) {
                context.error("Error while reading xtb files ", e);
                throw new RuntimeException(e);
            }
            context.warn("No matching locales for " + locale);
            return Optional.empty();
        }


        private Set<String> locales(String locale) {
            Set<String> result = new HashSet<>();
            result.add(locale);

            if (locale.contains("_")) {
                // according to spec, locale declaration can have 3 parts max
                if(locale.indexOf("_") != locale.lastIndexOf("_")) {
                    result.add(locale.substring(0, locale.lastIndexOf("_")));
                }
                result.add(locale.substring(0, locale.indexOf("_")));
            }
            return result;
        }

        private File mergeFiles(HashMap<String, Set<NodeList>> suitableFiles, Set<String> locales, TaskContext context) {
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
            generateAndWriteXTB(resultedCodeSet.values(), generated);
            return generated;
        }

        private void generateAndWriteXTB(Collection<Node> resultedCodeSet, File generated) {
            StringBuffer sb = new StringBuffer();

            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            sb.append("\n");
            sb.append("<!DOCTYPE translationbundle SYSTEM \"translationbundle.dtd\">");
            sb.append("\n");

            sb.append("<translationbundle lang=\"" + locale + "\">");
            sb.append("\n");

            resultedCodeSet.forEach(node -> {
                sb.append("  <translation id=\"");
                sb.append(node.getAttributes().getNamedItem("id").getNodeValue());
                sb.append("\" key=\"");
                sb.append(escape(node.getAttributes().getNamedItem("key").getNodeValue()));
                sb.append("\">");

                if(node.hasChildNodes()) {
                    StringBuffer innerContent = new StringBuffer();
                    for (int i = 0; i < node.getChildNodes().getLength(); i++) {
                        innerContent.append(getInnerContent(node.getChildNodes().item(i)));
                    }
                    String result = parse(innerContent.toString())
                            .stream()
                            .collect(Collectors.joining(""));
                    sb.append(result);
                }

                sb.append("</translation>");
                sb.append("\n");
            });

            sb.append("</translationbundle>");
            sb.append("\n");

            try {
                FileUtils.writeStringToFile(generated, sb.toString(), Charset.forName("UTF-8"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private String getInnerContent(Node node) {
            try {
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                StreamResult result = new StreamResult(new StringWriter());
                DOMSource source = new DOMSource(node);
                transformer.transform(source, result);
                return result.getWriter().toString();
            } catch(TransformerException ex) {
                throw new RuntimeException(ex);
            }
        }

        List<String> parse(String content) {
            List<String> parts = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char character = content.charAt(i);
                if (character == '<' && (i + 4) < content.length()) {
                    String tag = content.substring(i, i + 4);
                    if (tag.equals(ph_open)) {
                        if (sb.length() > 0) {
                            parts.add(escape(sb.toString()));
                        }
                        sb.setLength(0);
                        sb.append(ph_open);
                        int temp = i + ph_open.length();
                        boolean run = true;
                        while (run && temp < content.length()) {
                            boolean isSelfClosing = false;
                            boolean isClosing = false;
                            if(content.length() >= (temp + ph_self_close.length())) {
                                isSelfClosing = content.substring(temp, temp + ph_self_close.length()).equals(ph_self_close);
                            }
                            if(content.length() >= (temp + ph_close.length())) {
                                isClosing = content.substring(temp, temp + ph_close.length()).equals(ph_close);
                            }
                            if (isSelfClosing || isClosing) {
                                sb.append(isSelfClosing ? ph_self_close : ph_close);
                                parts.add(sb.toString());
                                i += sb.length() - 1;
                                run = false;
                                sb.setLength(0);
                            } else {
                                char current = content.charAt(temp);
                                sb.append(current);
                                temp++;
                            }
                        }
                    } else {
                        sb.append(character);
                    }
                } else {
                    sb.append(character);
                }
            }
            if (sb.length() > 0) {
                parts.add(escape(sb.toString()));
            }
            return parts;
        }

        private String escape(String part) {
            return part.replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("'", "&apos;")
                    .replace("\"", "&quot;")
                    .replace("&", "&amp;");
        }

    }
}
