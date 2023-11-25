package com.vertispan.j2cl.tools.closure;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.TokenStream;

import java.util.Objects;

/**
 * Modified copy of ConvertToDottedProperties. Changes from original:
 * <ul>
 *     <li>Called early in the build rather than very late, so that the unused now-dotted properties can be optimized
 *     as need be.</li>
 *     <li>Limit implementation to not apply to property accessors. This lets us simplify code slightly, and won't
 *     apply to our use cases, but there would be no downside for supporting this.</li>
 *     <li>Only applies to certain property patterns, to avoid accidentally inlining string properties that this
 *     shouldn't apply to.</li>
 *     <li>NodeUtil's members are package-protected, so required members are inlined here.</li>
 * </ul>
 * <p>
 * See <a href="https://groups.google.com/g/closure-compiler-discuss/c/3Gsd73xdt1U">mailing list discussion</a>.
 */
public class ConvertServiceLoaderProperties extends NodeTraversal.AbstractPostOrderCallback implements CompilerPass {

    private final AbstractCompiler compiler;

    public ConvertServiceLoaderProperties(AbstractCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public void process(Node externs, Node root) {
        NodeTraversal.traverse(compiler, root, this);
        System.err.println("ConvertServiceLoaderProperties running");
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
        if (Objects.requireNonNull(n.getToken()) == Token.OPTCHAIN_GETELEM || n.getToken() == Token.GETELEM) {
            Node left = n.getFirstChild();
            Node right = left.getNext();
            System.err.println(right);
            if (right.isStringLit() && right.getString().endsWith("$service$loader$key") && isValidPropertyName(FeatureSet.ES3, right.getString())) {
                left.detach();
                right.detach();

                Node newGetProp =
                        n.isGetElem()
                                ? IR.getprop(left, right.getString())
                                : (n.isOptionalChainStart()
                                ? IR.startOptChainGetprop(left, right.getString())
                                : IR.continueOptChainGetprop(left, right.getString()));
                n.replaceWith(newGetProp);
                compiler.reportChangeToEnclosingScope(newGetProp);
            }
        }
    }

    private static boolean isValidPropertyName(FeatureSet mode, String name) {
        if (isValidSimpleName(name)) {
            return true;
        } else {
            return mode.has(FeatureSet.Feature.KEYWORDS_AS_PROPERTIES) && TokenStream.isKeyword(name);
        }
    }
    static boolean isValidSimpleName(String name) {
        return TokenStream.isJSIdentifier(name)
                && !TokenStream.isKeyword(name)
                // no Unicode escaped characters - some browsers are less tolerant
                // of Unicode characters that might be valid according to the
                // language spec.
                // Note that by this point, Unicode escapes have been converted
                // to UTF-16 characters, so we're only searching for character
                // values, not escapes.
                && isLatin(name);
    }
    static boolean isLatin(String s) {
        int len = s.length();
        for (int index = 0; index < len; index++) {
            char c = s.charAt(index);
            if (c > LARGEST_BASIC_LATIN) {
                return false;
            }
        }
        return true;
    }
    static final char LARGEST_BASIC_LATIN = 0x7f;

}
