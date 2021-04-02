package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.PropertyTrackingConfig;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Wraps Maven's Xpp3Dom so dot-separated properties can be accessed.
 */
public class Xpp3DomConfigValueProvider implements PropertyTrackingConfig.ConfigValueProvider {
    private final Xpp3Dom config;

    public Xpp3DomConfigValueProvider(Xpp3Dom config) {
        this.config = config;
    }

    @Override
    public String readValueWithKey(String key) {
        return readValueWithKey(config, key, "");
    }

    // this method built to use tail-call recursion by hand, then automatically updated to use iteration
    private String readValueWithKey(Xpp3Dom config, String prefix, String remaining) {
        while (true) {
            assert prefix != null && remaining != null;
            assert config.getValue() == null;
            // using the longest prefix, look up the key
            if (config.getChild(prefix) != null) {
                // found it, if we have remaining, we need to handle them
                if (remaining.isEmpty()) {
                    // if this is null, that must be expected by the caller
                    return config.getChild(prefix).getValue();
                } else {
                    config = config.getChild(prefix);
                    prefix = remaining;
                    remaining = "";
                }
            } else {
                // peel off the last item, and try again
                int index = prefix.lastIndexOf('.');
                remaining = prefix.substring(index + 1) + '.' + remaining;
                prefix = prefix.substring(0, index);
            }
        }
    }

    //original, update this first and generate
//    private String readValueWithKey(Xpp3Dom config, String prefix, String remaining) {
//        assert prefix != null && remaining != null;
//        assert config.getValue() == null;
//        // using the longest prefix, look up the key
//        if (config.getChild(prefix) != null) {
//            // found it, if we have remaining, we need to handle them
//            if (remaining.isEmpty()) {
//                // if this is null, that must be expected by the caller
//                return config.getChild(prefix).getValue();
//            } else {
//                return readValueWithKey(config.getChild(prefix), remaining, "");
//            }
//        } else {
//            // peel off the last item, and try again
//            int index = prefix.lastIndexOf('.');
//            return readValueWithKey(config, prefix.substring(0, index), prefix.substring(index + 1) + '.' + remaining);
//        }
//    }

}
