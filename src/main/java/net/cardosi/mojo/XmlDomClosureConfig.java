package net.cardosi.mojo;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.*;
import java.util.stream.Collectors;

public class XmlDomClosureConfig implements ClosureBuildConfiguration {
    private final Xpp3Dom dom;
    private final String defaultScope;
    private final String defaultCompilationLevel;
    private final String defaultInitialScriptFilename;

    private final String defaultWebappDirectory;

    /**
     * @param dom the dom from the plugin invocation
     * @param defaultScope the expected scope based on the goal detected
     * @param defaultCompilationLevel the default compilation level based on the goal detected
     * @param artifactId the artifactId of the project being wrapped here
     * @param defaultWebappDirectory the current invocation's launch dir, so we all serve from the same place
     */
    public XmlDomClosureConfig(Xpp3Dom dom, String defaultScope, String defaultCompilationLevel, String artifactId, String defaultWebappDirectory) {
        this.dom = dom;
        this.defaultScope = defaultScope;
        this.defaultCompilationLevel = defaultCompilationLevel;
        this.defaultInitialScriptFilename = artifactId + "/" + artifactId + ".js";
        this.defaultWebappDirectory = defaultWebappDirectory;
    }

    @Override
    public String getClasspathScope() {
        Xpp3Dom elt = dom.getChild("classpathScope");
        return elt == null ? defaultScope : elt.getValue();
    }

    @Override
    public List<String> getEntrypoint() {
        Xpp3Dom entrypoint = dom.getChild("entrypoint");
        if (entrypoint == null) {
            return Collections.emptyList();
        }
        if (entrypoint.getValue() != null) {
            return Collections.singletonList(entrypoint.getValue());
        }
        return Arrays.stream(entrypoint.getChildren()).map(Xpp3Dom::getValue).collect(Collectors.toList());
    }

    @Override
    public Set<String> getExterns() {
        Xpp3Dom externs = dom.getChild("externs");

        return externs == null ?
                Collections.emptySet() :
                Arrays.stream(externs.getChildren())
                        .map(Xpp3Dom::getValue)
                        .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Map<String, String> getDefines() {
        Map<String, String> defines = new TreeMap<>();

        Xpp3Dom elt = dom.getChild("defines");

        if (elt != null) {
            for (Xpp3Dom child : elt.getChildren()) {
                defines.put(child.getName(), child.getValue());
            }
        }

        return defines;
    }

    @Override
    public String getWebappDirectory() {
        //default probably should override anything in the local DOM..?
        Xpp3Dom elt = dom.getChild("webappDirectory");
        return elt == null ? defaultWebappDirectory : elt.getValue();
    }

    @Override
    public String getInitialScriptFilename() {
        // must be defined locally, makes no sense to define globally...but we read out the artifactId as a default
        Xpp3Dom elt = dom.getChild("initialScriptFilename");
        return elt == null ? defaultInitialScriptFilename : elt.getValue();
    }

    @Override
    public String getCompilationLevel() {
        //if users want this controlled globally, properties are prob the best option
        Xpp3Dom elt = dom.getChild("compilationLevel");
        return elt == null ? defaultCompilationLevel : elt.getValue();
    }
}
