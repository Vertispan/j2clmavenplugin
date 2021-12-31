package com.vertispan.j2cl.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Splitter;
import com.google.common.collect.Streams;

public class BuildMap {
    private Project                   project;


    private Map<String, TypeInfo>     typeInfos                 = new HashMap<>();

    private Map<String, String>       pathToQualifiedSourceName = new HashMap<>();
    private Map<String, String>       qualifiedSourceNameToPath = new HashMap<>();

    private List<String>              childrenChangedFiles      = new ArrayList<>();;

    private Set<String>               changedFiles              = new HashSet<>();;

    private Set<String>               expandedFiles             = new HashSet<>();

    private List<String>              filesToDelete             = new ArrayList<>();

    private Map<String, ProjectFiles> dirToprojectFiles;

    public BuildMap(Project project, Map<String, ProjectFiles> dirToprojectFiles) {
        this.project = project;
        this.dirToprojectFiles = dirToprojectFiles;
    }

    public Project getProject() {
        return project;
    }

    public Set<String> getChangedFiles() {
        return changedFiles;
    }

    public Set<String> getExpandedFiles() {
        return expandedFiles;
    }

    public Map<String, ProjectFiles> getDirToprojectFiles() {
        return dirToprojectFiles;
    }

    /**
     *
     * @param dir
     */
    public void calculateChangeFiles(Path dir) {
        buildAndProcessChangedFiles(dir);
        populateFilesToDelete();
    }

    private void populateFilesToDelete() {
        // Merge all except added - which by it's nature has nothing nothing needed deleting
        for (ProjectFiles p : dirToprojectFiles.values()) {
            filesToDelete.addAll(p.getRemoved());
            filesToDelete.addAll(p.getUpdated());
        }
        filesToDelete.addAll(getExpandedFiles());
    }

    private void buildAndProcessChangedFiles(Path dir) {
        Map<String, TypeInfoDescr> typeInfoDescrs = readBuildMapDescrForAllFiles(dir, dirToprojectFiles);
        createBuildMaps(typeInfoDescrs);
        expandChangedFiles();

        // Populate the complete list of potentially changed files
        for(ProjectFiles projectFiles : dirToprojectFiles.values()) {
            changedFiles.addAll(projectFiles.getUpdated());
            changedFiles.addAll(projectFiles.getAdded());
        }
        changedFiles.addAll(expandedFiles);
    }

    public List<String> getFilesToDelete() {
        return filesToDelete;
    }

    public void expandChangedFiles() {
        for(ProjectFiles projectFiles : dirToprojectFiles.values()) {
            expandChangedFiles(projectFiles.getUpdated(), expandedFiles);
        }
        expandChangedFiles(childrenChangedFiles, expandedFiles);
    }
    public void expandChangedFiles(Collection<String> files, Set<String> expanded) {
        for (String file : files) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String   typeName = pathToQualifiedSourceName.get(file);
            TypeInfo typeInfo = typeInfos.get(typeName);
            expandChangedFiles(typeInfo, expanded);
        }
    }

    private void expandChangedFiles(TypeInfo typeInfo, Set<String> changedFiles) {
        // Anything that extends this is added to the set, and it also recurses through the extends
        for (TypeDependency dep : typeInfo.getSuperIn() ) {
            maybeAddNativeFile(dep.outgoing);
            changedFiles.add(qualifiedSourceNameToPath.get(dep.outgoing.getQualifiedSourceName()));
            expandChangedFiles(dep.outgoing, changedFiles);
        }

        // Anything that implements (or extends) this interface, is added to the set.
        // TODO Does this need to be done for transitive interface impl? (mdp)
        for (TypeDependency dep : typeInfo.getInterfacesIn() ) {
            maybeAddNativeFile(dep.outgoing);
            changedFiles.add(qualifiedSourceNameToPath.get(dep.outgoing.getQualifiedSourceName()));

            // Recurse the ancestors, as the interface may have default methods
            // that changes the call hieararchy of the implementor.
            expandChangedFiles(dep.outgoing, changedFiles);
        }

        // Now add all the dependencies
        for (TypeDependency dep : typeInfo.getMethodFieldIn()) {
            maybeAddNativeFile(dep.outgoing);
            changedFiles.add(qualifiedSourceNameToPath.get(dep.outgoing.getQualifiedSourceName()));
        }
    }

    private void maybeAddNativeFile(TypeInfo depTypeInfo) {
        if (depTypeInfo.getNativePathName() != null && !depTypeInfo.getNativePathName().isEmpty()) {
            changedFiles.add(depTypeInfo.getNativePathName());
        }
    }

    private void createBuildMaps(Map<String, TypeInfoDescr> typeInfoDescrs) {
        for (TypeInfoDescr typeInfoDescr : typeInfoDescrs.values()) {
            TypeInfo typeInfo = new TypeInfo(typeInfoDescr.qualifiedSourceName, typeInfoDescr.qualifiedBinaryName, typeInfoDescr.nativePathName);
            typeInfos.put(typeInfo.getQualifiedSourceName(), typeInfo);
        }

        for (TypeInfoDescr typeInfoDescr : typeInfoDescrs.values()) {
            // Set qualified source name
            TypeInfo outgoing = getType(typeInfoDescr.qualifiedSourceName);

            // Set extends (super) type if present.
            if (typeInfoDescr.superTypeName != null) {
                TypeInfo incoming = getType(typeInfoDescr.superTypeName);
                if (incoming != null) {
                    TypeDependency d = new TypeDependency(incoming, outgoing);
                    outgoing.setSuperOut(d);
                    incoming.getSuperIn().add(d);
                }
            }

            // Set enclosing type if present.
            if (typeInfoDescr.enclosingType != null) {
                TypeInfo enclosingType = getType(typeInfoDescr.enclosingType);
                outgoing.setEnclosingOut(enclosingType);
                enclosingType.getInnerTypes().add(outgoing);
            }

            if (!typeInfoDescr.innerTypes.isEmpty()) {
                for (String innerTypeName : typeInfoDescr.innerTypes) {
                    TypeInfo innerType = getType(innerTypeName);
                    outgoing.getInnerTypes().add(innerType);
                }
            }

            // Add implemented interfaces
            for (String type : typeInfoDescr.interfaces) {
                TypeInfo incoming = getType(type);
                if (incoming != null) {
                    TypeDependency d = new TypeDependency(incoming, outgoing);
                    outgoing.getInterfacesOut().add(d);
                    incoming.getInterfacesIn().add(d);
                }
            }

            // Add dependencies, that are not one of the above.
            for (String type : typeInfoDescr.dependencies) {
                TypeInfo incoming = getType(type);
                if (incoming != null) {
                    TypeDependency d = new TypeDependency(incoming,outgoing);
                    outgoing.getMethodFieldOut().add(d);
                    incoming.getMethodFieldIn().add(d);
                }
            }
        }
    }

    private Map<String, TypeInfoDescr> readBuildMapDescrForAllFiles(Path dir, Map<String, ProjectFiles> dirToprojectFiles) {
        Map<String, TypeInfoDescr> typeInfoDescrs = new HashMap<>();
        for (ProjectFiles projectFiles : dirToprojectFiles.values()) {
            // need to handle references.
            // Must also put in stuff that was just deleted, so we can handle the actual deletion.
            for (String javaFileName : Streams.concat(projectFiles.getAll().stream(),
                                                      projectFiles.getRemoved().stream()).collect(Collectors.toList())) {
                if (projectFiles.getAdded().contains(javaFileName)) {
                    // ignore just added files, they won't have a build.map yet
                    continue;
                }
                readBuildMapDescrForFileName(javaFileName, typeInfoDescrs, dir);
            }
        }

        return typeInfoDescrs;
    }

    private void readBuildMapDescrForFileName(String javaFileName, Map<String, TypeInfoDescr> typeInfoDescrs, Path dir) {
        if (javaFileName.endsWith(".java")) {
            String fileName = javaFileName.substring(0, javaFileName.lastIndexOf(".java"));
            String buildMapFileName = fileName + ".build.map";
            Path buildMapPath = dir.resolve("results").resolve(buildMapFileName);
            if (Files.notExists(buildMapPath)) {
                throw new RuntimeException("build.map files must exist for all changed .java files");
            }

            TypeInfoDescr typeInfoDescr = readBuildMapAsDescrs(buildMapPath, javaFileName, typeInfoDescrs);
            pathToQualifiedSourceName.put(javaFileName, typeInfoDescr.qualifiedSourceName);
            qualifiedSourceNameToPath.put(typeInfoDescr.qualifiedSourceName, javaFileName);
        }
    }

    private TypeInfo getType(String typeName) {
        TypeInfo type = typeInfos.get(typeName);
        return type;
    }

    TypeInfoDescr readBuildMapAsDescrs(Path buildMapPath, String javaFileName, Map<String, TypeInfoDescr> typeInfoDescrs) {
        List<String> lines;
        try {
            lines = Files.readAllLines(buildMapPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        TypeInfoDescr typeInfoDescr = null;
        for (LineReader reader = new LineReader(lines); reader.hasNext(); ) {
            String line = reader.getAndInc();
            if (!line.startsWith("- sources")) {
                throw new RuntimeException("Illegal File Format, it must start with '- sources' at line " + reader.lineNbr);
            }

            typeInfoDescr = readBuildMapSources(reader, typeInfoDescrs);

            readHierarchyAndInnerTypes(reader, typeInfoDescr, buildMapPath, javaFileName, typeInfoDescrs);

            line = reader.getAndInc();
            if (!line.startsWith("- interfaces")) {
                throw new RuntimeException("Illegal File Format, the next element must be '-interfaces' at line " + reader.lineNbr);
            }
            readBuildMapInterfaces(reader, typeInfoDescr);

            line = reader.getAndInc();
            if (!line.startsWith("- dependencies")) {
                throw new RuntimeException("Illegal File Format, the next element must be '-dependencies' at line " + reader.lineNbr);
            }
            readBuildMapDependencies(reader, typeInfoDescr);
        }

        return typeInfoDescr;
    }

    public List<String> getInnerTypes(String typeName) {
        TypeInfo typeInfo = typeInfos.get(typeName);
        List<String> innerTypes = new ArrayList<>();
        collectInnerTypes(typeInfo, innerTypes);
        return innerTypes;
    }

    private void collectInnerTypes(TypeInfo typeInfo, List<String> innerTypes) {
        for (TypeInfo innerType : typeInfo.getInnerTypes()) {
            innerTypes.add(innerType.getQualifiedSourceName());
            if (!typeInfo.getInnerTypes().isEmpty()) {
                collectInnerTypes(innerType, innerTypes);
            }
        }
    }

    public static class LineReader {
        private int lineNbr;
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

    static class TypeInfoDescr {
        private String qualifiedSourceName;
        private String superTypeName;       // optional
        private String qualifiedBinaryName; // optional if it's different to qualifiedSourceName, i.e. nested classes
        private String nativePathName;      // optional
        private String enclosingType;
        private List<String> innerTypes;
        private List<String> interfaces;
        private List<String> dependencies;  // dependencies that are not one of the above.

        public TypeInfoDescr(String qualifiedSourceName, String qualifiedBinaryName, String nativePathName) {
            this.qualifiedSourceName = qualifiedSourceName;
            this.qualifiedBinaryName =  qualifiedBinaryName != null && !qualifiedBinaryName.trim().isEmpty() ? qualifiedBinaryName : qualifiedSourceName;
            this.nativePathName = nativePathName;

            this.innerTypes = new ArrayList<>();
            this.interfaces = new ArrayList<>();
            this.dependencies = new ArrayList<>();
        }

        public List<String> dependencies() {
            return dependencies;
        }

        @Override public String toString() {
            return "TypeInfoDescr{" +
                   "qualifiedSourceName='" + qualifiedSourceName + '\'' +
                   ", qualifiedBinaryName='" + qualifiedBinaryName + '\'' +
                   ", superTypeName='" + superTypeName + '\'' +
                   ", nativePathName='" + nativePathName + '\'' +
                   ", enclosingType='" + enclosingType + '\'' +
                   ", innerTypes=" + innerTypes +
                   ", interfaces=" + interfaces +
                   ", dependencies=" + dependencies +
                   '}';
        }
    }

    TypeInfoDescr readBuildMapSources(LineReader reader, Map<String, TypeInfoDescr> typeInfoDescrs) {
        String[] segments = reader.getAndInc().split(":", -1);
        if (segments.length != 2) {
            throw new RuntimeException("First line of sources should have 2 segments, separated by ':'");
        }
        String qualifiedSourceName = segments[0];

        String qualifiedBinaryName = null;// optional if it's different to qualifiedSourceName, i.e. nested classes
        if (isNotEmpty(1, segments)) {
            checkFileFormat(segments[1], reader.lineNbr);
            qualifiedBinaryName = segments[1];
        }

        String nativePathName = null; // optional
        if (!reader.peekNext().startsWith("-") ) {
            // native file specified
            nativePathName = reader.getAndInc();
        }
        TypeInfoDescr typeInfoDescr = new TypeInfoDescr(qualifiedSourceName, qualifiedBinaryName, nativePathName);
        typeInfoDescrs.put(typeInfoDescr.qualifiedSourceName, typeInfoDescr);

        return typeInfoDescr;
    }

    public void readHierarchyAndInnerTypes(LineReader reader, TypeInfoDescr typeInfoDescr,
                                           Path buildMapPath, String javaFileName, Map<String, TypeInfoDescr> typeInfoDescrs) {
        String line = reader.getAndInc();
        if (!line.startsWith("- hierarchy")) {
            throw new RuntimeException("Illegal File Format, the next element must be '-hierarchy' at line " + reader.lineNbr);
        }
        if (!reader.peekNext().startsWith("-") ) {
            line = reader.getAndInc();
            String[] segments = line.split(":", -1);
            if (segments.length != 2) {
                throw new RuntimeException("First line of hierarchy should have 2 segments, separated by ':'");
            }

            String superTypeName = null; // optional
            if (isNotEmpty(0, segments)) {
                checkFileFormat(segments[0], reader.lineNbr);
                superTypeName = segments[0];
            }
            typeInfoDescr.superTypeName = superTypeName;

            String enclosingType = null; // optional
            if (isNotEmpty(1, segments)) {
                checkFileFormat(segments[1], reader.lineNbr);
                enclosingType = segments[1];
            }
            typeInfoDescr.enclosingType = enclosingType;
        }
        line = reader.getAndInc();
        if (!line.startsWith("- innerTypes")) {
            throw new RuntimeException("Illegal File Format, the next element must be '-innerTypes' at line " + reader.lineNbr);
        }
        if (!reader.peekNext().startsWith("-") ) {
            String[] innerTypes = reader.getAndInc().split(":", -1);

            String ext = ".build.map";
            if (innerTypes != null && innerTypes.length > 0) {
                for (String innerTypeName : innerTypes) {
                    typeInfoDescr.innerTypes.add(innerTypeName);

                    int lastDot = innerTypeName.lastIndexOf('.');
//                    String name = innerTypeName.substring(lastDot+1);
//                    typeInfos.get()
//
//
//                    int penDot = innerTypeName.lastIndexOf('.', lastDot-1);
//                    String fileName = innerTypeName.substring(penDot+1) + "$" + innerTypeName.substring(lastDot+1) + ext;

                    String fileName = buildMapPath.getFileName().toString();
                    fileName = fileName.substring(0, fileName.length() -ext.length()) + "$" + innerTypeName.substring(lastDot+1)  + ext;

                    Path innerBuildMapPath = buildMapPath.getParent().resolve( fileName );
                    if (!Files.exists(innerBuildMapPath)) {
                        throw new RuntimeException("InnerType .build.map file must exist: " + innerBuildMapPath);
                    }
                    readBuildMapAsDescrs(innerBuildMapPath, javaFileName, typeInfoDescrs);
                    // do not set pathToQualifiedSourceName as this will overwrite the root class of this file
                    qualifiedSourceNameToPath.put(innerTypeName, javaFileName); // innerType always refers back to file or root type
                }
            }
        }
    }

    void readBuildMapInterfaces(LineReader reader, TypeInfoDescr typeInfoDescr) {
       
        while( reader.hasNext() && !reader.peekNext().startsWith("- ")) {
            String typeName = reader.getAndInc();
            typeInfoDescr.interfaces.add(typeName);
        }
    }

    void readBuildMapDependencies(LineReader reader, TypeInfoDescr typeInfoDescr) {
        while(reader.hasNext() && !reader.peekNext().startsWith("-")) {
            String typeName = reader.getAndInc();
            typeInfoDescr.dependencies.add(typeName);
        }
    }

    private boolean isNotEmpty(int i, String[] x) {
        return x[i] != null && x[i].length() > 0;
    }

    private void checkFileFormat(String str, int i) {
        if (str.startsWith("-")) {
            throw new RuntimeException("Invalid File Formnat, '-' not allowed here at line " + i);
        }
    }


    /**
     * Clone the SourceMap to the TargetMap. Exclude MethodField TypeDependency references, these are not relevant to the parent
     * projects that consume this BuildMap.
     * @param target
     */
    public void cloneToTargetBuildMap(BuildMap target) {
        Map<String, TypeInfo> sourceMap = typeInfos;
        for (TypeInfo sourceType : sourceMap.values()) {
            sourceType.shallowCloneToMap(target.typeInfos);
        }

        for (TypeInfo sourceType : sourceMap.values()) {
            sourceType.cloneAllExceptMethodField(target.typeInfos);
        }

        target.pathToQualifiedSourceName.putAll(pathToQualifiedSourceName);
        target.qualifiedSourceNameToPath.putAll(qualifiedSourceNameToPath);

        for(ProjectFiles projectFiles : dirToprojectFiles.values()) {
            target.childrenChangedFiles.addAll(projectFiles.getUpdated());
        }

        target.childrenChangedFiles.addAll(expandedFiles);
        target.childrenChangedFiles.addAll(childrenChangedFiles);
    }

}
