package elite.types;

import java.util.*;

import javax.el.ELContext;

import org.operamasks.el.eval.ELEngine;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Token;

/**
 * Type checker pass that runs after parsing and before execution.
 * Walks the ELProgram AST and checks type consistency:
 * - Verifies annotated types exist (are resolvable)
 * - Checks that expression types match annotations
 * - Reports all errors collected during inference
 */
public class TypeChecker {

    private final ELContext elctx;
    private final List<String> errors;
    private boolean strict; // if true, errors are fatal

    public TypeChecker(ELContext elctx) {
        this.elctx = elctx;
        this.errors = new ArrayList<>();
        this.strict = true;
    }

    /**
     * Run type checking on a parsed ELNode (expression or statement).
     * @return true if no fatal errors were found
     */
    public boolean check(ELNode node) {
        TypeInferrer inferrer = new TypeInferrer(elctx);
        checkNode(node, inferrer);
        errors.addAll(inferrer.getErrors());
        return !hasFatalErrors();
    }

    /**
     * Run type checking on a list of ELNodes (e.g., program statements).
     */
    public boolean checkAll(List<ELNode> nodes) {
        TypeInferrer inferrer = new TypeInferrer(elctx);
        for (ELNode node : nodes) {
            checkNode(node, inferrer);
        }
        errors.addAll(inferrer.getErrors());
        return !hasFatalErrors();
    }

    /**
     * Run type checking on an ELProgram (definitions + expressions).
     */
    public boolean checkProgram(Iterable<ELNode> defs, Iterable<ELNode> exps) {
        TypeInferrer inferrer = new TypeInferrer(elctx);

        // First pass: register all definitions with their types
        for (ELNode def : defs) {
            registerDefinition(def, inferrer);
        }

        // Second pass: check all expressions
        for (ELNode exp : exps) {
            checkNode(exp, inferrer);
        }

        errors.addAll(inferrer.getErrors());
        return !hasFatalErrors();
    }

    public List<String> getErrors() { return Collections.unmodifiableList(errors); }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean hasFatalErrors() { return strict && !errors.isEmpty(); }

    public void setStrict(boolean strict) { this.strict = strict; }

    // ---- Internal ----

    private void registerDefinition(ELNode node, TypeInferrer inferrer) {
        if (node instanceof ELNode.DEFINE) {
            ELNode.DEFINE def = (ELNode.DEFINE) node;
            // If there's a type annotation, validate it exists
            if (def.type != null && !def.type.isEmpty()) {
                // Just run the inferrer on the expression — it will
                // resolve the annotation and record errors if invalid
                inferrer.infer(def.expr);
                // Re-register with the annotated type in the environment
                inferrer.infer(node);
            }
        }
    }

    private void checkNode(ELNode node, TypeInferrer inferrer) {
        if (node == null) return;

        // Run inference regardless — collects errors for type annotations
        Type inferred = inferrer.infer(node);
        node.inferredType = inferred;

        // For DEFINE nodes, check type consistency
        if (node instanceof ELNode.DEFINE) {
            checkDefine((ELNode.DEFINE) node, inferred);
        }

        // Recursively check children
        if (node instanceof ELNode.LAMBDA) {
            checkNode(((ELNode.LAMBDA) node).body, inferrer);
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
            for (ELNode arg : app.args) {
                checkNode(arg, inferrer);
            }
        }
        if (node instanceof ELNode.MATCH) {
            ELNode.MATCH match = (ELNode.MATCH) node;
            for (ELNode.CASE caseNode : match.alts) {
                for (ELNode body : caseNode.bodies) {
                    checkNode(body, inferrer);
                }
            }
            if (match.deflt != null) checkNode(match.deflt, inferrer);
        }
        if (node instanceof ELNode.BLOCK) {
            checkNode(((ELNode.BLOCK) node).body, inferrer);
        }
    }

    private void checkDefine(ELNode.DEFINE def, Type inferred) {
        // If the definition has a type annotation and we inferred something
        // incompatible, report it
        if (def.type != null && !def.type.isEmpty() && inferred != Type.DYNAMIC) {
            // The actual annotation validation is done by TypeInferrer
            // (via resolveTypeAnnotation). Here we just check type consistency.

            // Check if the annotated return type for a lambda matches the body
            if (def.expr instanceof ELNode.LAMBDA) {
                ELNode.LAMBDA lambda = (ELNode.LAMBDA) def.expr;
                if (lambda.rtype != null && !lambda.rtype.isEmpty()) {
                    Type bodyType = lambda.body.inferredType;
                    if (bodyType != null && bodyType != Type.DYNAMIC) {
                        // Try to resolve the return type
                        try {
                            Class<?> cls = ELEngine.resolveJavaClass(elctx, lambda.rtype);
                            Type expectedType = Type.fromClass(cls);
                            if (bodyType != expectedType && !bodyType.isSubtypeOf(expectedType)) {
                                errors.add("Type mismatch: expected '" + lambda.rtype +
                                    "' but body has type '" + bodyType.toTypeString() + "'");
                            }
                        } catch (Exception e) {
                            // rtype resolution failed — already reported by TypeInferrer
                        }
                    }
                }
            }
        }
    }
}
