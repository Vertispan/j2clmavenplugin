package com.vertispan.j2cl.mojo;

import com.vertispan.j2cl.build.task.BuildLog;
import org.apache.maven.plugin.logging.Log;

public class MavenLog implements BuildLog {

    private final Log logger;

    public MavenLog(Log logger) {
        this.logger = logger;
    }

    @Override
    public void debug(String msg) {
        logger.debug(msg);
    }

    @Override
    public void info(String msg) {
        logger.info(msg);
    }

    @Override
    public void warn(String msg) {
        logger.warn(msg);
    }

    @Override
    public void warn(String msg, Throwable t) {
        logger.warn(msg, t);
    }

    @Override
    public void warn(Throwable t) {
        logger.warn(t);
    }

    @Override
    public void error(String msg) {
        logger.error(msg);
    }

    @Override
    public void error(String msg, Throwable t) {
        logger.error(msg, t);
    }

    @Override
    public void error(Throwable t) {
        logger.error(t);
    }
}
