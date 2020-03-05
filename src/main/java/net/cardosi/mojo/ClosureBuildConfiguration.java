package net.cardosi.mojo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes how to run the Closure Compiler in a j2cl-based project, in a way that we can implement
 * not just in goals but also wrap up a xml dom and pass it around. All implementations of this must
 * be consistent in how they name their parameters to avoid driving users insane - naming should be
 * derrived from the property names.
 */
public interface ClosureBuildConfiguration {
    String getClasspathScope();

    List<String> getEntrypoint();

    Set<String> getExterns();

    Map<String, String> getDefines();

    String getWebappDirectory();

    String getInitialScriptFilename();

    String getCompilationLevel();

    boolean getRewritePolyfills();

//    List<String> getIncludedJsZips();

    default String hash() {
        //TODO externs need to have their _contents_ hashed instead

        Hash hash = new Hash();
        hash.append(getClasspathScope());
        getEntrypoint().forEach(s -> hash.append(s));
        getDefines()
                .forEach((key, value) -> {
                    hash.append(key);
                    hash.append(value);
        });
        // not considering webappdir or script filename for now, should just copy the output at the end every time
        hash.append(getCompilationLevel());

        return hash.toString();
    }
}
