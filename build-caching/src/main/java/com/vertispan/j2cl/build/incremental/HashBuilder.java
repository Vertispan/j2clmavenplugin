package com.vertispan.j2cl.build.incremental;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class HashBuilder {

    private Set<Integer> hashes = new TreeSet<>();

    public void addField(String name, String type) {
        hashes.add(Objects.hash(type, name));
    }

    public void addMethod(String name, String type, String params) {
        hashes.add(Objects.hash(type, name, params));
    }

    @Override
    public String toString() {
        return Objects.hash(hashes.toArray(new Integer[hashes.size()])) + "";
    }
}
