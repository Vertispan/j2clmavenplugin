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
