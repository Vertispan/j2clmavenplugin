package net.cardosi.mojo;

import com.google.javascript.jscomp.DependencyOptions;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes how to run the Closure Compiler in a j2cl-based project, in a way that we can implement
 * not just in goals but also wrap up a xml dom and pass it around. All implementations of this must
 * be consistent in how they name their parameters to avoid driving users insane - naming should be
 * derived from the property names.
 */
public interface ClosureBuildConfiguration {
    String getClasspathScope();

    @Deprecated
    List<String> getEntrypoint();

    Set<String> getExterns();

    Map<String, String> getDefines();

    String getWebappDirectory();

    String getInitialScriptFilename();

    String getCompilationLevel();

    @Deprecated
    DependencyOptions.DependencyMode getDependencyMode();

    String getLanguageOut();

    boolean getRewritePolyfills();

    boolean getCheckAssertions();

//    List<String> getIncludedJsZips();

    boolean getSourcemapsEnabled();

    default String hash() {
        //TODO externs need to have their _contents_ hashed instead

        Hash hash = new Hash();
        hash.append(getClasspathScope());
        getEntrypoint().forEach(s -> hash.append(s));
        hash.append(getDependencyMode().name());
        getDefines()
                .forEach((key, value) -> {
                    hash.append(key);
                    hash.append(value);
        });

        // not considering webappdir for now, should just copy the output at the end every time
        hash.append(getInitialScriptFilename());

        hash.append(getCompilationLevel());

        hash.append(getLanguageOut());

        BitSet flags = new BitSet();
        flags.set(0, getRewritePolyfills());
        flags.set(1, getCheckAssertions());
        flags.set(2, getSourcemapsEnabled());
        hash.append(flags.toByteArray());
        return hash.toString();
    }
}
