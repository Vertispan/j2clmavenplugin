package com.vertispan.j2cl.build.incremental;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class BuildMapHashReader {

    private final Path path;

    public BuildMapHashReader(Path buildMapPath) {
        this.path = buildMapPath;
    }

    public String hash() {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (LineReader reader = new LineReader(lines); reader.hasNext(); ) {
            String line = reader.getAndInc();
            if(line.startsWith("- hash")) {
                return reader.getAndInc();
            }
        }

        throw new RuntimeException("Unable to find hash in " + path);
    }
}
