package com.vertispan.j2cl.build.task;

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
