package com.vertispan.j2cl.build.incremental;

import java.util.List;

public class LineReader {
    int lineNbr;
    List<String> lines;

    public LineReader(List<String> lines) {
        this.lines = lines;
    }

    String getAndInc() {
        String line = lines.get(lineNbr++);


        // skip any empty lines
        while (lineNbr < lines.size() &&
                lines.get(lineNbr).trim().isEmpty()) {
            lineNbr++;
        }

        return line;
    }

    String peekNext() {
        return lines.get(lineNbr);
    }

    boolean hasNext() {
        return lineNbr < lines.size();
    }

}
