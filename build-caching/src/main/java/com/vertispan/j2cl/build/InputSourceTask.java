//package com.vertispan.j2cl.build;
//
//import com.vertispan.j2cl.build.task.Config;
//import com.vertispan.j2cl.build.task.OutputTypes;
//import com.vertispan.j2cl.build.task.TaskFactory;
//
//// note that we don't bother to register this, it can't be overridden
//public class InputSourceTask extends TaskFactory {
//    @Override
//    public String getOutputType() {
//        return OutputTypes.INPUT_SOURCES;
//    }
//
//    @Override
//    public String getTaskName() {
//        return "default";
//    }
//
//    @Override
//    public Task resolve(Project project, Config config) {
//        return outputPath -> {
//
//        };
//    }
//}
