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
package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.TaskFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Looks for tasks which are available on the current classpath and offers
 * the currently selected implementations.
 *
 * Probably not part of the public API except to create an instance and
 * select mappings.
 */
public class TaskRegistry {
    private final Map<String, TaskFactory> outputTypeToTaskMappings = new HashMap<>();

    /**
     * Create with the default mappings, which will be looked up in the service loader.
     * @param outputToNameMappings configured mappings provided from the build tool
     */
    public TaskRegistry(Map<String, String> outputToNameMappings) {
        ServiceLoader<TaskFactory> loader = ServiceLoader.load(TaskFactory.class);
        for (TaskFactory task : loader) {
            String mapping = outputToNameMappings.get(task.getOutputType());
            if (mapping == null) {
                mapping = "default";
            }
            if (task.getTaskName().equals(mapping)) {
                outputTypeToTaskMappings.put(task.getOutputType(), task);
            }
        }
//        System.out.println(outputTypeToTaskMappings);
    }

    public TaskFactory taskForOutputType(String outputType) {
        if (outputType.equals(OutputTypes.INPUT_SOURCES)) {
            //TODO create something specific for this? or hijack it when setting up the input instead?
        }
        return outputTypeToTaskMappings.get(outputType);
    }
}
