package com.vertispan.j2cl.tools;

import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.ErrorFormat;
import com.google.javascript.jscomp.MessageFormatter;
import com.google.javascript.jscomp.SortingErrorManager;
import com.vertispan.j2cl.build.task.BuildLog;

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
    }
}
