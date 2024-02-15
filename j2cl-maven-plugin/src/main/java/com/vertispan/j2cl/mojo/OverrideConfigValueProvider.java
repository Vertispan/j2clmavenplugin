/*
 * Copyright © 2021 j2cl-maven-plugin authors
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
