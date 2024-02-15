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
package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskContext;
import com.vertispan.j2cl.build.task.TaskFactory;

import javax.annotation.Nullable;
import java.io.File;

/**
 * Disables annotation processors from within this build, and relies instead on some other tooling
 * already generating sources (hopefully incrementally). It is assumed that the source directories
 * passed in the Project already contain that generated source - the WatchService will then notice
 * them change.
 */
@AutoService(TaskFactory.class)
public class SkipAptTask extends BytecodeTask {

    public static final String SKIP_TASK_NAME = "skip";

    @Override
    public String getOutputType() {
        return OutputTypes.BYTECODE;
    }

    @Override
    public String getTaskName() {
        return SKIP_TASK_NAME;
    }

    @Override
    public String getVersion() {
        return super.getVersion() + "0";
    }

    @Nullable
    @Override
    protected File getGeneratedClassesDir(TaskContext context) {
        // By returning null, we signal to javac not to attempt to run annotation processors.
        return null;
    }
}
