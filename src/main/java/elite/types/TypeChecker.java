package elite.types;

import java.util.*;

import javax.el.ELContext;

import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Position;
import org.operamasks.el.parser.Token;
import static org.operamasks.el.resources.Resources.*;

/**
 * Type checker pass that runs after parsing and before execution.
 */
public class TypeChecker {

    private final ELContext elctx;
    private final String filename;
    private final List<String> errors;
    private boolean strict;

    public TypeChecker(ELContext elctx) {
        this(elctx, null);
    }

    public TypeChecker(ELContext elctx, String filename) {
        this.elctx = elctx;
        this.filename = filename;
        this.errors = new ArrayList<>();
        this.strict = true;
    }

    public boolean checkProgram(Iterable<ELNode> defs, Iterable<ELNode> exps) {
        TypeInferrer inferrer = new TypeInferrer(elctx);

        for (ELNode def : defs) {
            registerDefinition(def, inferrer);
        }

        for (ELNode exp : exps) {
            checkNode(exp, inferrer);
        }

        // Persist type bindings so subsequent evals can see them
        inferrer.persistTypes();

        // Format errors with position info
        for (String err : inferrer.getErrors()) {
            errors.add(formatError(err));
        }

        return !hasFatalErrors();
    }

    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean hasFatalErrors() { return strict && !errors.isEmpty(); }
    public void setStrict(boolean strict) { this.strict = strict; }

    // ---- Internal ----

    private String formatError(String message) {
        if (filename != null) {
            return filename + ":" + message;
        }
        return message;
    }

    private void registerDefinition(ELNode node, TypeInferrer inferrer) {
        if (node instanceof ELNode.DEFINE) {
            ELNode.DEFINE def = (ELNode.DEFINE) node;
            // inferDefine validates the define's own type annotation
            // and recursively validates the expression (LAMBDA params etc.)
            inferrer.infer(node);
        }
    }

    private void checkNode(ELNode node, TypeInferrer inferrer) {
        if (node == null) return;

        Type inferred = inferrer.infer(node);
        node.inferredType = inferred;

        if (node instanceof ELNode.LAMBDA) {
            ELNode.LAMBDA lambda = (ELNode.LAMBDA) node;
            for (ELNode.DEFINE var : lambda.vars) {
                if (var.type != null && !var.type.isEmpty()) {
                    inferrer.infer(var);
                }
            }
            checkNode(lambda.body, inferrer);
        }
        if (node instanceof ELNode.Unary) {
            checkNode(((ELNode.Unary) node).right, inferrer);
        }
        if (node instanceof ELNode.Binary) {
            ELNode.Binary bin = (ELNode.Binary) node;
            checkNode(bin.left, inferrer);
            checkNode(bin.right, inferrer);
        }
        if (node instanceof ELNode.APPLY) {
            ELNode.APPLY app = (ELNode.APPLY) node;
            for (ELNode arg : app.args) checkNode(arg, inferrer);
        }
        if (node instanceof ELNode.MATCH) {
            ELNode.MATCH match = (ELNode.MATCH) node;
            for (ELNode.CASE caseNode : match.alts) {
                for (ELNode body : caseNode.bodies) checkNode(body, inferrer);
            }
            if (match.deflt != null) checkNode(match.deflt, inferrer);
        }
        if (node instanceof ELNode.BLOCK) {
            checkNode(((ELNode.BLOCK) node).body, inferrer);
        }
    }
}
