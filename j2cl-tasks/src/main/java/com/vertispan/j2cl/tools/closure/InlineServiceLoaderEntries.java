package com.vertispan.j2cl.tools.closure;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import org.jspecify.nullness.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.vertispan.j2cl.tools.closure.ConvertServiceLoaderProperties.isValidPropertyName;

/**
 * Find and replaces references to keyed service loader requests with their implementations,
 * avoiding the need to compile in the entire map of known entries.
 */
public class InlineServiceLoaderEntries extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {
    static final DiagnosticType J2CL_VERTISPAN_SERVICELOADER_UNKNOWN_NODE =
            DiagnosticType.warning("J2CL_VERTISPAN_SERVICELOADER_UNKNOWN_NODE",
                    "Unexpected node type");

    public static final String KEY_SUFFIX = "$service$loader$key";
    private final AbstractCompiler compiler;

    private final Map<String, Node> writes = new HashMap<>();
    private final Map<String, Node> reads = new HashMap<>();

    public InlineServiceLoaderEntries(AbstractCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public void process(Node externs, Node root) {
        // Collect all references GETELEM references where the string literal ends with our specifies suffix
        NodeTraversal.traverse(compiler, root, this);

        // Match writes to corresponding reads, replacing reads with the inlined body of the written call.
        // Leave the writes intact in case of dynamic (or unsolvable) reads
        rewriteStaticReads();
    }

    @Override
    public void visit(NodeTraversal t, Node n, @Nullable Node parent) {
//        if (parent == null) {
//            return;
//        }
        if (Objects.requireNonNull(n.getToken()) == Token.OPTCHAIN_GETELEM || n.getToken() == Token.GETELEM) {
            Node left = n.getFirstChild();
            Node right = left.getNext();
            if (right.isStringLit() && right.getString().endsWith(KEY_SUFFIX) && isValidPropertyName(FeatureSet.ES3, right.getString())) {
                if (parent.getToken() == Token.ASSIGN) {
                    // record write
                    System.out.println("write " + compiler.toSource(parent));
                    writes.put(right.getString(), n.getNext().getFirstChild().getNext().getNext().getFirstChild().getFirstChild());
                } else if (parent.getToken() == Token.CALL) {
                    // record read
                    System.out.println("read " + compiler.toSource(parent));
                    reads.put(right.getString(), n);
                } else {
                    t.report(parent, J2CL_VERTISPAN_SERVICELOADER_UNKNOWN_NODE);
                }
            }
        }
    }

    private void rewriteStaticReads() {
        for (Map.Entry<String, Node> read : reads.entrySet()) {
            Node write = writes.get(read.getKey());
            if (write == null) {
                continue;
            }
            Node replacement = write.cloneTree(true);
            read.getValue().replaceWith(replacement);
            compiler.reportChangeToEnclosingScope(replacement);
        }
    }
}
