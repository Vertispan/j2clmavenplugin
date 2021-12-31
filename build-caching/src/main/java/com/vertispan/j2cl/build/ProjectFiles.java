package com.vertispan.j2cl.build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProjectFiles {
    private String dir;

    private List<String>        removed        = new ArrayList<>(); // files
    private Set<String>         updated        = new HashSet<>(); // files
    private Set<String>         added          = new HashSet<>(); // files
    private Set<String>         all; // files

    public ProjectFiles(String dir, Set<String> all) {
        this.dir = dir;
        this.all = all;
    }

    public String getDir() {
        return dir;
    }

    public List<String> getRemoved() {
        return removed;
    }

    public Set<String> getUpdated() {
        return updated;
    }

    public Set<String> getAdded() {
        return added;
    }

    public Set<String> getAll() {
        return all;
    }

    @Override public String toString() {
        return "ProjectFiles{" +
               "removed=" + removed +
               ", updated=" + updated +
               ", added=" + added +
               ", all=" + all +
               '}';
    }
}