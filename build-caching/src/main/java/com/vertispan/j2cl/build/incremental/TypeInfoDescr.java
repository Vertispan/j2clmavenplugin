package com.vertispan.j2cl.build.incremental;

import java.util.ArrayList;
import java.util.List;

public class TypeInfoDescr {
    String qualifiedSourceName;
    String superTypeName;       // optional
    String qualifiedBinaryName; // optional if it's different to qualifiedSourceName, i.e. nested classes
    String nativePathName;      // optional
    String enclosingType;
    List<String> innerTypes;
    List<String> interfaces;
    List<String> dependencies;  // dependencies that are not one of the above.

    String hash;

    public TypeInfoDescr(String qualifiedSourceName, String qualifiedBinaryName, String nativePathName) {
        this.qualifiedSourceName = qualifiedSourceName;
        this.qualifiedBinaryName = qualifiedBinaryName != null && !qualifiedBinaryName.trim().isEmpty() ? qualifiedBinaryName : qualifiedSourceName;
        this.nativePathName = nativePathName;

        this.innerTypes = new ArrayList<>();
        this.interfaces = new ArrayList<>();
        this.dependencies = new ArrayList<>();
    }

    public List<String> dependencies() {
        return dependencies;
    }

    @Override
    public String toString() {
        return "TypeInfoDescr{" +
                "qualifiedSourceName='" + qualifiedSourceName + '\'' +
                ", qualifiedBinaryName='" + qualifiedBinaryName + '\'' +
                ", superTypeName='" + superTypeName + '\'' +
                ", nativePathName='" + nativePathName + '\'' +
                ", enclosingType='" + enclosingType + '\'' +
                ", innerTypes=" + innerTypes +
                ", interfaces=" + interfaces +
                ", dependencies=" + dependencies +
                ", hash=" + hash +
                '}';
    }
}
