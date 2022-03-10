package com.vertispan.j2cl.build.task;

/**
 * Our own log api, which can forward to the calling tool's log.
 */
// TODO support isDebug(), etc, to avoid extra logging?
// TODO support trace logging, for some middle ground before debug?
public interface BuildLog {

    void debug(String msg);

    void info(String msg);

    void warn(String msg);

    void warn(String msg, Throwable t);

    void warn(Throwable t);

    void error(String msg);

    void error(String msg, Throwable t);

    void error(Throwable t);
}
