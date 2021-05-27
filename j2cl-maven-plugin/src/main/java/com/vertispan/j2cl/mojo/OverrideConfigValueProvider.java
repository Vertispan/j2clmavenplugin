package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.PropertyTrackingConfig;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OverrideConfigValueProvider implements PropertyTrackingConfig.ConfigValueProvider {
    private final PropertyTrackingConfig.ConfigValueProvider config;
    private final Map<String, ConfigNode> overrides;

    public OverrideConfigValueProvider(PropertyTrackingConfig.ConfigValueProvider config, Map<String, String> overrides) {
        this.config = config;
        this.overrides = overrides.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> new SimpleStringNode(e.getKey(), e.getValue())));
    }
    static class SimpleStringNode extends AbstractConfigNode {
        private final String value;

        protected SimpleStringNode(String path, String value) {
            super(path);
            this.value = value;
        }

        @Override
        public String readString() {
            return value;
        }

        @Override
        public File readFile() {
            throw new IllegalStateException("Not a file");
        }

        @Override
        public List<ConfigNode> getChildren() {
            return Collections.emptyList();
        }
    }

    @Override
    public ConfigNode findNode(String path) {
        if (overrides.containsKey(path)) {
            return overrides.get(path);
        }
        return config.findNode(path);
    }
}
