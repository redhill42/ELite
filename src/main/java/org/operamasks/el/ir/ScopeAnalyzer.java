/*
 * Copyright 2006-2026 Daniel Yuan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.operamasks.el.ir;

import java.util.*;

import org.operamasks.el.parser.DefaultVisitor;
import org.operamasks.el.parser.ELNode;

/**
 * Pre-pass scope and capture analysis for the IR compiler.
 *
 * <p>Walks the AST to build a scope tree and determine, for each function/lambda:
 * <ul>
 *   <li>Which of its local variables are <em>captured by</em> inner closures
 *       (must use STORE_GLOBAL/PUSH_GLOBAL in the enclosing function)</li>
 *   <li>Which variables it <em>captures from</em> enclosing scopes
 *       (must use PUSH_GLOBAL/STORE_GLOBAL in the closure body)</li>
 *   <li>Which captured variables are mutated
 *       (must emit STORE_GLOBAL, not just PUSH_GLOBAL)</li>
 *   <li>Which variables are purely local — never captured
 *       (safe for STORE_VAR/PUSH_VAR local slots)</li>
 * </ul>
 */
public class ScopeAnalyzer {

    /** Analysis result for a single function/scope. */
    public static class ScopeAnalysis {
        /** Variables I capture from enclosing scopes — use PUSH_GLOBAL to read. */
        public final Set<String> freeVarNames;
        /** Subset of freeVarNames that I mutate — use STORE_GLOBAL to write. */
        public final Set<String> mutableFreeVars;
        /** My variables that are captured by inner closures — use STORE_GLOBAL to write. */
        public final Set<String> capturedByInner;
        /** Non-captured, non-param local variables — use STORE_VAR/PUSH_VAR slots. */
        public final List<String> localOnlyNames;
        /** Total local slots needed (paramCount + localOnlyNames.size()). */
        public final int localSlotCount;

        public ScopeAnalysis(Set<String> freeVarNames,
                             Set<String> mutableFreeVars,
                             Set<String> capturedByInner,
                             List<String> localOnlyNames,
                             int paramCount) {
            this.freeVarNames = Collections.unmodifiableSet(freeVarNames);
            this.mutableFreeVars = Collections.unmodifiableSet(mutableFreeVars);
            this.capturedByInner = Collections.unmodifiableSet(capturedByInner);
            this.localOnlyNames = Collections.unmodifiableList(localOnlyNames);
            this.localSlotCount = paramCount + localOnlyNames.size();
        }

        /** Empty analysis for functions with no captures and no inner closures. */
        public static final ScopeAnalysis EMPTY = new ScopeAnalysis(
            Set.of(), Set.of(), Set.of(), List.of(), 0);

        @Override
        public String toString() {
            return "ScopeAnalysis{free=" + freeVarNames
                + ", mutableFree=" + mutableFreeVars
                + ", capturedByInner=" + capturedByInner
                + ", locals=" + localOnlyNames
                + ", slots=" + localSlotCount + "}";
        }
    }

    /**
     * Analyze the top-level program scope.
     *
     * @param defs  top-level DEFINE nodes (function/class definitions)
     * @param exps  top-level expression nodes
     * @param paramNames  names already allocated as parameters (e.g. for a lambda's vars)
     * @return analysis for the top-level scope
     */
    public static ScopeAnalysis analyze(List<ELNode> defs,
                                        List<ELNode> exps,
                                        List<String> paramNames) {
        Analyzer a = new Analyzer(paramNames);
        // First pass: discover all scopes and record declarations/references/mutations
        if (defs != null) {
            for (ELNode def : defs) {
                a.scan(def);
            }
        }
        if (exps != null) {
            for (ELNode exp : exps) {
                a.scan(exp);
            }
        }
        // Second pass: compute captures bottom-up
        a.computeCaptures();
        return a.topLevelResult();
    }

    /**
     * Analyze a single lambda body. Used when compiling nested lambdas
     * where the enclosing scope is already known.
     *
     * @param lambda     the lambda node
     * @param enclosingDeclared  variables declared in the enclosing scope
     * @param enclosingCaptured  variables in enclosing scope that are captured-by-inner
     * @return analysis for the lambda's scope
     */
    public static ScopeAnalysis analyzeLambda(ELNode.LAMBDA lambda,
                                              Set<String> enclosingDeclared,
                                              Set<String> enclosingCaptured) {
        List<String> paramNames = new ArrayList<>();
        for (ELNode.DEFINE v : lambda.vars) {
            if (!"_".equals(v.id)) {
                paramNames.add(v.id);
            }
        }
        Analyzer a = new Analyzer(paramNames);
        a.scan(lambda.body);
        a.computeCapturesForLambda(enclosingDeclared, enclosingCaptured);
        return a.lambdaResult();
    }

    // ── Internal analyzer ────────────────────────────────────────────────

    private static class Analyzer extends DefaultVisitor {
        final List<String> paramNames;
        // Stack of scopes being built; top is the current scope
        final Deque<Scope> scopeStack = new ArrayDeque<>();
        // All lambda scopes discovered (for bottom-up processing)
        final List<Scope> allScopes = new ArrayList<>();

        Analyzer(List<String> paramNames) {
            this.paramNames = (paramNames != null) ? paramNames : List.of();
            // Create the top-level scope
            Scope top = new Scope(null);
            top.declared.addAll(this.paramNames);
            scopeStack.push(top);
        }

        // ── Scope discovery ──────────────────────────────────────────

        @Override
        public void visit(ELNode.LAMBDA e) {
            // A lambda (or BLOCK, which extends LAMBDA) creates a new scope.
            // Its parameters are declared in that scope.
            Scope child = new Scope(scopeStack.peek());
            scopeStack.peek().children.add(child);
            allScopes.add(child);
            scopeStack.push(child);

            // Register parameters as declared variables in this scope
            for (ELNode.DEFINE v : e.vars) {
                if (!"_".equals(v.id)) {
                    child.declared.add(v.id);
                }
            }

            // Visit the body inside the child scope
            scan(e.body);

            scopeStack.pop();
        }

        // ── Variable declarations ────────────────────────────────────

        @Override
        public void visit(ELNode.DEFINE e) {
            // A DEFINE declares a variable in the current scope
            scopeStack.peek().declared.add(e.id);
            // Visit the initializer expression
            scan(e.expr);
            scan(e.meta);
        }

        // ── Variable references (reads) ──────────────────────────────

        @Override
        public void visit(ELNode.IDENT e) {
            scopeStack.peek().referenced.add(e.id);
        }

        // ── Variable mutations (writes) ──────────────────────────────

        @Override
        public void visit(ELNode.ASSIGN e) {
            // Record the left side as mutated
            if (e.left instanceof ELNode.IDENT ident) {
                scopeStack.peek().mutated.add(ident.id);
                scopeStack.peek().referenced.add(ident.id);
            }
            // Visit both sides (DefaultVisitor does visitBinary for ASSIGN)
            scan(e.left);
            scan(e.right);
        }

        @Override
        public void visit(ELNode.INC e) {
            if (e.right instanceof ELNode.IDENT ident) {
                scopeStack.peek().mutated.add(ident.id);
                scopeStack.peek().referenced.add(ident.id);
            }
            scan(e.right);
        }

        @Override
        public void visit(ELNode.DEC e) {
            if (e.right instanceof ELNode.IDENT ident) {
                scopeStack.peek().mutated.add(ident.id);
                scopeStack.peek().referenced.add(ident.id);
            }
            scan(e.right);
        }

        // ── COMPOUND handling: special case for top-level exps ───────

        @Override
        public void visit(ELNode.COMPOUND e) {
            scan(e.exps);
        }

        // ── Bottom-up capture computation ────────────────────────────

        /**
         * Compute captures for all scopes bottom-up.
         */
        void computeCaptures() {
            // Process scopes bottom-up (innermost first)
            for (int i = allScopes.size() - 1; i >= 0; i--) {
                Scope scope = allScopes.get(i);
                computeCapturesFor(scope);
            }
        }

        /**
         * Compute captures for a single lambda scope.
         */
        private void computeCapturesFor(Scope scope) {
            Scope enclosing = scope.parent;

            // Collect all identifiers referenced in this scope
            // (including from any nested lambdas, minus their own declared vars)
            Set<String> allRefs = new HashSet<>(scope.referenced);

            // A variable is "captured from enclosing" if:
            // - It's referenced in this scope (or any nested scope within)
            // - It's NOT declared in this scope
            // - It IS declared in the enclosing scope
            // (If it's not declared in the enclosing scope either, it's a global — not our concern)

            for (String ref : allRefs) {
                if (!scope.declared.contains(ref) && enclosing.declared.contains(ref)) {
                    scope.captures.add(ref);
                    if (scope.mutated.contains(ref)) {
                        scope.mutableCaptures.add(ref);
                    }
                    // Propagate upward: the enclosing scope has this var captured by inner
                    enclosing.capturedByInner.add(ref);
                }
            }
        }

        /**
         * Compute captures for a lambda given explicit enclosing info.
         * Used by analyzeLambda() when analyzing a lambda in isolation.
         */
        void computeCapturesForLambda(Set<String> enclosingDeclared,
                                      Set<String> enclosingCaptured) {
            if (allScopes.isEmpty()) {
                // Only the lambda's own scope exists
                Scope scope = scopeStack.peek();
                Set<String> allRefs = new HashSet<>(scope.referenced);

                for (String ref : allRefs) {
                    if (!scope.declared.contains(ref) && enclosingDeclared.contains(ref)) {
                        scope.captures.add(ref);
                        if (scope.mutated.contains(ref)) {
                            scope.mutableCaptures.add(ref);
                        }
                        enclosingCaptured.add(ref);
                    }
                }
            } else {
                // Has nested scopes — process bottom-up then do the top
                for (int i = allScopes.size() - 1; i >= 0; i--) {
                    Scope s = allScopes.get(i);
                    Set<String> allRefs = new HashSet<>(s.referenced);
                    for (String ref : allRefs) {
                        Scope enc = s.parent;
                        if (!s.declared.contains(ref) && enc.declared.contains(ref)) {
                            s.captures.add(ref);
                            if (s.mutated.contains(ref)) {
                                s.mutableCaptures.add(ref);
                            }
                            enc.capturedByInner.add(ref);
                        }
                    }
                }
                // Now handle the lambda's own scope vs enclosing
                Scope scope = scopeStack.peek();
                Set<String> allRefs = new HashSet<>(scope.referenced);
                for (String ref : allRefs) {
                    if (!scope.declared.contains(ref) && enclosingDeclared.contains(ref)) {
                        scope.captures.add(ref);
                        if (scope.mutated.contains(ref)) {
                            scope.mutableCaptures.add(ref);
                        }
                        enclosingCaptured.add(ref);
                    }
                }
            }
        }

        // ── Result construction ──────────────────────────────────────

        ScopeAnalysis topLevelResult() {
            Scope top = scopeStack.peek();
            // Process top-level results
            Set<String> freeVars = Set.of(); // top-level has no enclosing scope
            Set<String> mutableFree = Set.of();
            Set<String> capturedByInner = Collections.unmodifiableSet(
                new HashSet<>(top.capturedByInner));

            // Local-only names = declared - params - capturedByInner
            List<String> localOnly = new ArrayList<>();
            for (String name : top.declared) {
                if (!paramNames.contains(name) && !capturedByInner.contains(name)) {
                    localOnly.add(name);
                }
            }

            return new ScopeAnalysis(freeVars, mutableFree, capturedByInner,
                                     localOnly, paramNames.size());
        }

        ScopeAnalysis lambdaResult() {
            Scope scope = scopeStack.peek();
            Set<String> capturedByInner = Collections.unmodifiableSet(
                new HashSet<>(scope.capturedByInner));

            List<String> localOnly = new ArrayList<>();
            for (String name : scope.declared) {
                if (!paramNames.contains(name) && !capturedByInner.contains(name)) {
                    localOnly.add(name);
                }
            }

            return new ScopeAnalysis(
                Collections.unmodifiableSet(new HashSet<>(scope.captures)),
                Collections.unmodifiableSet(new HashSet<>(scope.mutableCaptures)),
                capturedByInner,
                localOnly,
                paramNames.size());
        }
    }

    // ── Scope tree node ────────────────────────────────────────────────

    static class Scope {
        final Scope parent;
        final List<Scope> children = new ArrayList<>();

        // Variables declared in this scope (params + define)
        final Set<String> declared = new LinkedHashSet<>();
        // All IDENT references found in this scope's body
        final Set<String> referenced = new HashSet<>();
        // IDENT targets of ASSIGN, ASSIGNOP, INC, DEC
        final Set<String> mutated = new HashSet<>();

        // Computed bottom-up:
        // Variables from the ENCLOSING scope that this scope captures
        final Set<String> captures = new HashSet<>();
        // Subset of captures that are mutated in this scope
        final Set<String> mutableCaptures = new HashSet<>();
        // Variables declared in THIS scope that are captured by any descendant
        final Set<String> capturedByInner = new HashSet<>();

        Scope(Scope parent) {
            this.parent = parent;
        }
    }
}
