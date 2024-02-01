/*
 * Copyright © 2023 j2cl-maven-plugin authors
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
package com.vertispan.j2cl.build;

import java.util.List;
import java.util.Map;

public class TaskSummaryDiskFormat {
    private String projectKey;
    private String outputType;
    private String taskImpl;
    private String taskImplVersion;

    private List<InputDiskFormat> inputs;

    private Map<String, String> configs;

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getOutputType() {
        return outputType;
    }

    public void setOutputType(String outputType) {
        this.outputType = outputType;
    }

    public String getTaskImpl() {
        return taskImpl;
    }

    public void setTaskImpl(String taskImpl) {
        this.taskImpl = taskImpl;
    }

    public String getTaskImplVersion() {
        return taskImplVersion;
    }

    public void setTaskImplVersion(String taskImplVersion) {
        this.taskImplVersion = taskImplVersion;
    }

    public List<InputDiskFormat> getInputs() {
        return inputs;
    }

    public void setInputs(List<InputDiskFormat> inputs) {
        this.inputs = inputs;
    }

    public Map<String, String> getConfigs() {
        return configs;
    }

    public void setConfigs(Map<String, String> configs) {
        this.configs = configs;
    }

    public static class InputDiskFormat {
        private String projectKey;
        private String outputType;
        private Map<String, String> fileHashes;

        public String getProjectKey() {
            return projectKey;
        }

        public void setProjectKey(String projectKey) {
            this.projectKey = projectKey;
        }

        public String getOutputType() {
            return outputType;
        }

        public void setOutputType(String outputType) {
            this.outputType = outputType;
        }

        public Map<String, String> getFileHashes() {
            return fileHashes;
        }

        public void setFileHashes(Map<String, String> fileHashes) {
            this.fileHashes = fileHashes;
        }
    }
}
