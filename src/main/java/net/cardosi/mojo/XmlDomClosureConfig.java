package net.cardosi.mojo;

import com.google.javascript.jscomp.DependencyOptions;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.*;
import java.util.stream.Collectors;

public class XmlDomClosureConfig implements ClosureBuildConfiguration {
    private final Xpp3Dom dom;
    private final String defaultScope;
    private final String defaultCompilationLevel;
    private final String defaultLanguageOut;
    private final boolean defaultRewritePolyfills;
    private final String defaultInitialScriptFilename;

    @Deprecated
    private final DependencyOptions.DependencyMode defaultDependencyMode;

    private final boolean defaultSourcemapsEnabled;

    private final String defaultWebappDirectory;

    /**
     * @param dom the dom from the plugin invocation
     * @param defaultScope the expected scope based on the goal detected
     * @param defaultCompilationLevel the default compilation level based on the goal detected
     * @param defaultLanguageOut the default output langague level based on the goal detected
     * @param defaultRewritePolyfills whether or not closure should rewrite polyfills by default
     * @param artifactId the artifactId of the project being wrapped here
     * @param defaultDependencyMode (deprecated, will be gone soon)
     * @param defaultSourcemapsEnabled true if sourcemaps should be enabled by default
     * @param defaultWebappDirectory the current invocation's launch dir, so we all serve from the same place
     */
    public XmlDomClosureConfig(Xpp3Dom dom, String defaultScope, String defaultCompilationLevel, String defaultLanguageOut, boolean defaultRewritePolyfills, String artifactId, DependencyOptions.DependencyMode defaultDependencyMode, boolean defaultSourcemapsEnabled, String defaultWebappDirectory) {
        this.dom = dom;
        this.defaultScope = defaultScope;
        this.defaultCompilationLevel = defaultCompilationLevel;
        this.defaultLanguageOut = defaultLanguageOut;
        this.defaultRewritePolyfills = defaultRewritePolyfills;
        this.defaultInitialScriptFilename = artifactId + "/" + artifactId + ".js";
        this.defaultDependencyMode = defaultDependencyMode;
        this.defaultSourcemapsEnabled = defaultSourcemapsEnabled;
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
    public DependencyOptions.DependencyMode getDependencyMode() {
        Xpp3Dom elt = dom.getChild("dependencyMode");
        return elt == null ? defaultDependencyMode : DependencyOptions.DependencyMode.valueOf(elt.getValue());
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

    @Override
    public String getLanguageOut() {
        //if users want this controlled globally, properties are prob the best option
        Xpp3Dom elt = dom.getChild("languageOut");
        return elt == null ? defaultLanguageOut : elt.getValue();
    }

    @Override
    public boolean getCheckAssertions() {
        Xpp3Dom elt = dom.getChild("checkedAssertions");
        // any time we use XmlDomClosureConfig (various watch modes), we assume that we want to default to true
        return elt == null || elt.getValue().equalsIgnoreCase("true");
    }

    @Override
    public boolean getRewritePolyfills() {
        Xpp3Dom elt = dom.getChild("rewritePolyfills");
        return elt == null ? defaultRewritePolyfills : elt.getValue().equalsIgnoreCase("true");
    }

    @Override
    public boolean getSourcemapsEnabled() {
        Xpp3Dom elt = dom.getChild("enableSourcemaps");
        return elt == null ? defaultSourcemapsEnabled : elt.getValue().equalsIgnoreCase("true");
    }
}
