package com.vertispan.j2cl.build.incremental;

public class TypeDependency {
    TypeInfo incoming;
    TypeInfo outgoing;

    public TypeDependency(TypeInfo incoming, TypeInfo outgoing) {
        this.incoming = incoming;
        this.outgoing = outgoing;
    }

    public TypeInfo incoming() {
        return incoming;
    }

    public TypeInfo outgoing() {
        return outgoing;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        TypeDependency that = (TypeDependency) o;

        if (!incoming.equals(that.incoming)) {
            return false;
        }
        return outgoing.equals(that.outgoing);
    }

    @Override
    public int hashCode() {
        int result = incoming.hashCode();
        result = 31 * result + outgoing.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "TypeDependency{" +
               "incoming=" + incoming +
               ", outgoing=" + outgoing +
               '}';
    }
}
