package com.vertispan.j2cl.build;

import java.util.*;

public class ProjectFiles {
    private String dir;

    private List<String>        removed        = new ArrayList<>(); // files
    private Map<String, Boolean>         updated        = new HashMap<>(); // files
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

    public Map<String, Boolean> getUpdated() {
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
               ", updated=" + updated.keySet() +
               ", added=" + added +
               ", all=" + all +
               '}';
    }
}