package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.PropertyTrackingConfig;

import java.io.File;
import java.util.List;
import java.util.Map;

public class OverrideConfigValueProvider implements PropertyTrackingConfig.ConfigValueProvider {
    private final PropertyTrackingConfig.ConfigValueProvider config;
    private final Map<String, String> overrides;

    public OverrideConfigValueProvider(PropertyTrackingConfig.ConfigValueProvider config, Map<String, String> overrides) {
        this.config = config;
        this.overrides = overrides;
    }

    @Override
    public String readStringWithKey(String key) {
        if (overrides.containsKey(key)) {
            return overrides.get(key);
        }
        return config.readStringWithKey(key);
    }

    @Override
    public File readFileWithKey(String key) {
        if (overrides.containsKey(key)) {
            return new File(overrides.get(key));
        }
        return config.readFileWithKey(key);
    }

    @Override
    public List<File> readFilesWithKey(String key) {
        if (overrides.containsKey(key)) {
            throw new IllegalStateException("Override doesn't support readFilesWithKey");
        }
        return config.readFilesWithKey(key);
    }
}
