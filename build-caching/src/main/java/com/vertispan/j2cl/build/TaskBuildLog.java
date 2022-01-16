package com.vertispan.j2cl.build;

import com.vertispan.j2cl.build.task.BuildLog;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * BuildLog implementation that forwards log entries to the upstream log, and also
 * writes log details to a file for later review.
 *
 * This file is not considered part of the cached output, and its format may change, so that
 * we can replay it for later builds.
 */
public class TaskBuildLog implements BuildLog {
    private final BuildLog buildLog;
    private final String debugName;
    private final PrintStream logFilePrintStream;

    public TaskBuildLog(BuildLog buildLog, String debugName, Path logFilePath) throws FileNotFoundException {
        this.buildLog = buildLog;
        this.debugName = debugName;
        this.logFilePrintStream = new PrintStream(logFilePath.toFile());
    }

    @Override
    public synchronized void debug(String msg) {
        buildLog.debug(debugName + ": " + msg);
        logFilePrintStream.print("[DEBUG] ");
        logFilePrintStream.println(msg);
    }

    @Override
    public synchronized void info(String msg) {
        buildLog.info(debugName + ": " + msg);
        logFilePrintStream.print("[INFO]  ");
        logFilePrintStream.println(msg);
    }

    @Override
    public synchronized void warn(String msg) {
        buildLog.warn(debugName + ": " + msg);
        logFilePrintStream.print("[WARN]  ");
        logFilePrintStream.println(msg);
    }

    @Override
    public synchronized void warn(String msg, Throwable t) {
        buildLog.warn(debugName + ": " + msg, t);
        logFilePrintStream.print("[WARN]  ");
        logFilePrintStream.println(msg);
        printStackTrace(t, "[WARN]  ");
    }

    @Override
    public synchronized void warn(Throwable t) {
        buildLog.warn(debugName, t);
        printStackTrace(t, "[WARN]  ");
    }

    @Override
    public synchronized void error(String msg) {
        buildLog.error(debugName + ": " + msg);
        logFilePrintStream.print("[ERROR] ");
        logFilePrintStream.println(msg);
    }

    @Override
    public synchronized void error(String msg, Throwable t) {
        buildLog.error(debugName + ": " + msg, t);
        logFilePrintStream.print("[ERROR] ");
        logFilePrintStream.println(msg);
        printStackTrace(t, "[ERROR] ");
    }

    @Override
    public synchronized void error(Throwable t) {
        buildLog.error(debugName, t);
        printStackTrace(t, "[ERROR] ");
    }

    // Helpers adapted from gwtproject/gwt, does not suppress repeated lines like jre does
    private void printStackTrace(Throwable t, String prefix) {
        printStackTrace(t, prefix, "", Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    private void printStackTrace(Throwable t, String indent, String prefix, Set<Throwable> seen) {
        if (!seen.add(t)) {
            return;
        }
        logFilePrintStream.print(indent);
        logFilePrintStream.print(prefix);
        logFilePrintStream.println(t);

        StackTraceElement[] elts = t.getStackTrace();//note that this must do a defensive clone
        for (StackTraceElement elt : elts) {
            logFilePrintStream.print(prefix);
            logFilePrintStream.print(indent);
            logFilePrintStream.print("\tat ");
            logFilePrintStream.println(elt);
        }

        Throwable[] suppressed = t.getSuppressed();
        for (Throwable throwable : suppressed) {
            printStackTrace(throwable, indent + "\t", "Suppressed: ", seen);
        }

        Throwable cause = t.getCause();
        if (cause != null) {
            printStackTrace(cause, indent, "Caused by: ", seen);
        }
    }
}
