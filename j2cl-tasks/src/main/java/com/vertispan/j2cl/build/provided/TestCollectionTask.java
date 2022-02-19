package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.vertispan.j2cl.build.task.*;
import org.apache.commons.io.FileUtils;

import java.nio.file.*;

/**
 * Runs annotation processors and collects the test summary output.
 */
@AutoService(TaskFactory.class)
public class TestCollectionTask extends TaskFactory {
    // While this is an internal task, it is still possible to provide an alternative implementation
    public static final String TEST_COLLECTION_OUTPUT_TYPE = "test_summary";

    private static final String TEST_SUMMARY_FILENAME = "test_summary.json";
    private static final PathMatcher TEST_SUMMARY_JSON = FileSystems.getDefault().getPathMatcher("glob:" + TEST_SUMMARY_FILENAME);
    private static final PathMatcher TEST_SUITE = withSuffix(".testsuite");

    @Override
    public String getOutputType() {
        return TEST_COLLECTION_OUTPUT_TYPE;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "0";
    }

    @Override
    public Task resolve(Project project, Config config) {
        // gather possible inputs so we can get the test summary file
        // we assume here that the user will correctly depend on the junit apt, might revise this later
        Input apt = input(project, OutputTypes.BYTECODE).filter(TEST_SUMMARY_JSON, TEST_SUITE);
        return new FinalOutputTask() {
            @Override
            public void execute(TaskContext context) throws Exception {
                // TODO If both contain a test summary, we should fail, rather than overwrite
                // Or even better, merge?

                for (CachedPath entry : apt.getFilesAndHashes()) {
                    Path outputFile = context.outputPath().resolve(entry.getSourcePath());
                    Files.createDirectories(outputFile.getParent());
                    Files.copy(entry.getAbsolutePath(), outputFile);
                }
            }

            @Override
            public void finish(TaskContext taskContext) throws Exception {
                Files.createDirectories(config.getWebappDirectory());
                FileUtils.copyDirectory(taskContext.outputPath().toFile(), config.getWebappDirectory().toFile());
            }
        };
    }
}
