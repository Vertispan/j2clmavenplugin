package com.vertispan.j2cl.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TypeInfo {
    private String     qualifiedSourceName;
    private String     qualifiedBinaryName;
    private String     nativePathName;

    private TypeDependency superOut;
    private TypeInfo       enclosingOut;

    private List<TypeDependency> superIn = new ArrayList<>();

    private List<TypeDependency> interfacesOut  = new ArrayList<>();;
    private List<TypeDependency> interfacesIn  = new ArrayList<>();;

    private List<TypeInfo>       innerTypes  = new ArrayList<>();;

    private List<TypeDependency> methodFieldOut  = new ArrayList<>();;
    private List<TypeDependency> methodFieldIn  = new ArrayList<>();;

    public TypeInfo(String qualifiedSourceName,
                    String qualifiedBinaryName, String nativePathName) {
        this.qualifiedSourceName = qualifiedSourceName;
        this.qualifiedBinaryName = qualifiedBinaryName;
        this.nativePathName = nativePathName;
    }

    public String getUniqueId() {
        return qualifiedBinaryName; // just temporary, until we figure out what this should be, or maybe just use getQualifiedBinaryName
    }

    public String getQualifiedBinaryName() {
        return qualifiedBinaryName;
    }

    public String getQualifiedSourceName() {
        return qualifiedSourceName;
    }

    public String getNativePathName() {
        return nativePathName;
    }

    public void setNativePathName(String nativePathName) {
        this.nativePathName = nativePathName;
    }

    public TypeDependency getSuperOut() {
        return superOut;
    }

    public void setSuperOut(TypeDependency superOut) {
        this.superOut = superOut;
    }

    public List<TypeDependency> getSuperIn() {
        return superIn;
    }

    public TypeInfo getEnclosingOut() {
        return enclosingOut;
    }

    public void setEnclosingOut(TypeInfo enclosingOut) {
        this.enclosingOut = enclosingOut;
    }

    public List<TypeInfo> getInnerTypes() {
        return innerTypes;
    }

    public List<TypeDependency> getMethodFieldOut() {
        return methodFieldOut;
    }

    public List<TypeDependency> getMethodFieldIn() {
        return methodFieldIn;
    }

    public List<TypeDependency> getInterfacesOut() {
        return interfacesOut;
    }

    public List<TypeDependency> getInterfacesIn() {
        return interfacesIn;
    }

    @Override public String toString() {
        return "TypeInfo{" +
               ", qualifiedSourceName='" + qualifiedSourceName + '\'' +
               ", qualifiedBinaryName='" + qualifiedBinaryName + '\'' +
//               ", nativePathName='" + nativePathName + '\'' +
//               ", superOut=" + superOut +
//               ", enclosingOut=" + enclosingOut.getQualifiedBinaryName() +
//               ", superIn=" + superIn +
//               ", interfacesOut=" + interfacesOut +
//               ", interfacesIn=" + interfacesIn +
//               ", innerTypes=" + innerTypes +
//               ", methodFieldOut=" + methodFieldOut +
//               ", methodFieldIn=" + methodFieldIn +
               '}';
    }

    /**
     * All the TypeInfo's must be shallow cloned first, so that all the qualifiedBinaryName resolve in a map lookup
     * to the new instance.
     * @param typeInfoMap
     */
    public void shallowCloneToMap(Map<String, TypeInfo> typeInfoMap) {
        TypeInfo cloned = new TypeInfo(qualifiedSourceName, qualifiedBinaryName, nativePathName);

        typeInfoMap.put(cloned.getQualifiedSourceName(), cloned);
    }

    /**
     * Clone everything except the MethodField TypeDependency references, these are not relevant to the parent
     * projects that consume this BuildMap.
     *
     * @param typeInfoMap
     */
    public void cloneAllExceptMethodField(Map<String, TypeInfo> typeInfoMap) {
        TypeInfo outgoing = typeInfoMap.get(qualifiedSourceName); // cloned this

        // clone super Dep
        if (superOut != null){
            TypeInfo   incoming = typeInfoMap.get(superOut.incoming.getQualifiedSourceName());
            TypeDependency superDep = new TypeDependency(incoming, outgoing);
            outgoing.setSuperOut(superDep);
            incoming.getSuperIn().add(superDep);
        }

        // clone implements
        for(TypeDependency interfaceDep : interfacesOut) {
            TypeInfo incoming = typeInfoMap.get(interfaceDep.incoming.getQualifiedSourceName());
            TypeDependency inerfaceDep = new TypeDependency(incoming, outgoing);
            incoming.getInterfacesIn().add(inerfaceDep);
            outgoing.getInterfacesOut().add(inerfaceDep);
        }

        // clone innerTypes
        for(TypeInfo innerType : innerTypes) {
            TypeInfo incoming = typeInfoMap.get(innerType.getQualifiedSourceName());
            incoming.setEnclosingOut(outgoing);
            outgoing.getInnerTypes().add(incoming);
        }
    }
}
