/*
 * Copyright © 2022 j2cl-maven-plugin authors
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
package com.vertispan.j2cl.tools;

import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.ErrorFormat;
import com.google.javascript.jscomp.MessageFormatter;
import com.google.javascript.jscomp.SortingErrorManager;
import com.vertispan.j2cl.build.task.BuildLog;

/**
 * Error manager implementation for Closure Compiler, to append errors and warnings to the build log.
 */
public class LoggingErrorReportGenerator implements SortingErrorManager.ErrorReportGenerator {
    private final Compiler compiler;
    private final BuildLog log;

    LoggingErrorReportGenerator(Compiler compiler, BuildLog log) {
        this.compiler = compiler;
        this.log = log;
    }

    @Override
    public void generateReport(SortingErrorManager manager) {
        MessageFormatter formatter = ErrorFormat.SINGLELINE.toFormatter(compiler, false);
        manager.getWarnings().forEach(w -> {
            log.warn(w.format(CheckLevel.WARNING, formatter));
        });
        manager.getErrors().forEach(e -> {
            log.error(e.format(CheckLevel.ERROR, formatter));
        });
        if (manager.getTypedPercent() > 0) {
            log.info(String.format("%d error(s), %d warning(s), %.1f%% typed%n", manager.getErrorCount(), manager.getWarningCount(), manager.getTypedPercent()));
        } else {
            log.info(String.format("%d error(s), %d warning(s)%n", manager.getErrorCount(), manager.getWarningCount()));
        }
    }
}
