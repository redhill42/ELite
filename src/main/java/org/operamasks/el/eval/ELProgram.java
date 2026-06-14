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

package org.operamasks.el.eval;

import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import javax.el.ELContext;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import javax.el.ELException;

import elite.lang.Closure;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Position;
import org.operamasks.el.resolver.ClassResolver;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.el.eval.closure.LiteralClosure;
import org.operamasks.el.eval.closure.FieldClosure;
import org.operamasks.el.ir.IRBuilder;
import org.operamasks.el.ir.IRBytecodeCompiler;
import org.operamasks.el.ir.IRFunction;
import org.operamasks.el.ir.IRInterpreter;
import org.operamasks.el.ir.CompilationError;
import org.operamasks.util.Utils;

public class ELProgram implements Serializable
{
    private List<Module> mods;
    private List<String> libs;
    private List<String> imps;
    private List<ELNode> defs;
    private List<ELNode> exps;

    /** Enable IR-based evaluation for this program. Default: true (IR native). */
    private boolean useIREvaluation = true;

    /**
     * Optimization level for expression evaluation.
     * <ul>
     *   <li>0 — AST interpreter only (for parser/AST validation)</li>
     *   <li>1 — IR interpreter, no optimization passes (conservative IR)</li>
     *   <li>2 — IR interpreter with optimizations (default; fall back to AST)</li>
     *   <li>3 — JVM bytecode (fall back to IR for unsupported ops)</li>
     * </ul>
     * Read from system property {@code elite.opt.level}; defaults to 2 (IR).
     */
    static final int OPT_LEVEL = Integer.getInteger("elite.opt.level", 2);

    /**
     * When true, bytecode/IR failures throw instead of silently falling back.
     * Set via system property {@code elite.strict} or programmatically.
     * Recommended for development/testing; off by default for production resilience.
     */
    static boolean STRICT_BYTECODE = Boolean.getBoolean("elite.strict");

    /** Print fallback/debug messages to stderr. Off by default (quiet mode). */
    static final boolean DEBUG = Boolean.getBoolean("elite.debug");

    /** @return the list of definition statements */
    public List<ELNode> getDefinitions() { return defs; }
    /** @return the list of expression/statement nodes */
    public List<ELNode> getExpressions() { return exps; }

    /** Enable or disable IR-based evaluation. */
    public void setIREvaluation(boolean enabled) { this.useIREvaluation = enabled; }
    /** @return whether IR-based evaluation is enabled. */
    public boolean isIREvaluation() { return useIREvaluation; }

    private static final long serialVersionUID = 3112245719728771823L;

    public ELProgram() {
        this.mods = new ArrayList<Module>();
        this.libs = new ArrayList<String>();
        this.imps = new ArrayList<String>();
        this.defs = new ArrayList<ELNode>();
        this.exps = new ArrayList<ELNode>();
    }

    public void addModule(String name, String prefix) {
        Module module = new Module(name, prefix);
        if (!mods.contains(module)) {
            mods.add(module);
        }
    }

    public void addLibrary(String name) {
        if (!libs.contains(name)) {
            libs.add(name);
        }
    }

    public void addImport(String imp) {
        if (!imps.contains(imp)) {
            imps.add(imp);
        }
    }

    public void addExpression(ELNode exp) {
        (isDef(exp) ? defs : exps).add(exp);
    }

    private static boolean isDef(ELNode node) {
        if (node instanceof ELNode.DEFINE) {
            ELNode exp = ((ELNode.DEFINE)node).expr;
            return exp instanceof ELNode.LAMBDA || exp instanceof ELNode.CLASSDEF;
        } else {
            return false;
        }
    }

    public Object execute(ELContext elctx) {
        return execute(elctx, null, 1);
    }

    public Object execute(ELContext elctx, String file, int line) {
        FunctionMapper fm = elctx.getFunctionMapper();
        VariableMapper vm = elctx.getVariableMapper();

        // The function mapper is not significant in XEL, we built it for all
        // expressions for performance reasons. The variable mapper will
        // be built for individual expression.
        if (fm != null) {
            FunctionMapperBuilder fmb = new FunctionMapperBuilder(fm);
            for (ELNode node : exps) {
                node.applyFunctionMapper(fmb);
            }
            fm = fmb.build();
        }

        // Evaluate expressions in global context.
        EvaluationContext env = new EvaluationContext(elctx, fm, vm);
        Frame frame = StackTrace.addFrame(elctx, "__toplevel__", file, Position.make(line, 1));

        // Execute program in three steps:
        try {
            // 1) import modules and classes
            importModules(elctx);
            importFunctions(elctx);
            importPackages(elctx);

            // 2) define function and class for forward reference
            for (ELNode node : defs) {
                frame.setPos(node.pos);
                node.getValue(env);
            }

            // 3) execute statements using selected evaluation strategy
            return evaluate(defs, exps, env, elctx, frame);
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    /**
     * Execute expressions using the selected optimization level.
     * <p>
     * Level 0 (AST): always use AST interpreter.
     * Level 1 (IR):  use unoptimized IR interpreter.
     * Level 2 (IR):  use optimized IR interpreter.
     * Level 3 (BC):  compile to JVM bytecode; on CompilationError, fall back to IR.
     * <p>
     * TRAMPOLINE opcodes are handled inline by the IR interpreter and bytecode
     * compiler (calling back to AST for individual expressions), so there is
     * no full-program fallback. Exceptions during execution propagate directly.
     */
    private Object evaluate(List<ELNode> defs, List<ELNode> exps,
                            EvaluationContext env, javax.el.ELContext elctx,
                            Frame frame) {
        if (exps.isEmpty()) return null;

        switch (OPT_LEVEL) {
            case 0:
                return evaluateAST(exps, frame, env);

            case 1: {
                // Conservative IR — no optimization passes (constant folding, type specialization skipped)
                // The IR interpreter handles TRAMPOLINE opcodes inline via AST evaluation,
                // so there is no need for a full-program fallback.
                IRFunction irFn = IRBuilder.compileWithDefs(defs, exps, false);
                return new IRInterpreter(elctx, irFn, env).execute(null);
            }

            case 2: {
                IRFunction irFn = IRBuilder.compileWithDefs(defs, exps);
                return new IRInterpreter(elctx, irFn, env).execute(null);
            }

            case 3: default: {
                IRFunction irFn = IRBuilder.compileWithDefs(defs, exps);
                try {
                    IRBytecodeCompiler.CompiledFunction cf =
                        IRBytecodeCompiler.compile(irFn);
                    return cf.execute(elctx, null);
                } catch (CompilationError e) {
                    if (STRICT_BYTECODE)
                        throw new ELException(
                            "Bytecode compilation failed in strict mode. " +
                            "Set -Delite.strict=false to allow IR fallback.", e);
                    if (DEBUG) System.err.println("[elite] bytecode fallback: " + e.getMessage());
                    return new IRInterpreter(elctx, irFn, env).execute(null);
                }
                // VerifyError and other Errors propagate — they're compiler bugs
            }
        }
    }

    /** Execute expressions using the AST tree-walking interpreter. */
    private static Object evaluateAST(List<ELNode> exps, Frame frame,
                                       EvaluationContext env) {
        Object result = null;
        for (ELNode node : exps) {
            frame.setPos(node.pos);
            result = node.getValue(env);
        }
        return result;
    }

    // Implementation

    static class Module {
        String name;
        String prefix;

        Module(String name, String prefix) {
            this.name = name;
            this.prefix = prefix;
        }

        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj instanceof Module) {
                Module other = (Module)obj;
                return name.equals(other.name) &&
                       (prefix == null ? other.prefix == null : prefix.equals(other.prefix));
            } else {
                return false;
            }
        }
    }

    private void importModules(ELContext elctx) {
        if (!mods.isEmpty()) {
            MethodResolver resolver = MethodResolver.getInstance(elctx);
            for (Module mod : mods) {
                Class cls = findClass(elctx, mod.name);
                resolver.addModule(elctx, cls, mod.prefix);
                for (Field field : cls.getFields()) {
                    importField(elctx, field, mod.prefix);
                }
            }
        }
    }

    private void importFunctions(ELContext elctx) {
        if (!libs.isEmpty()) {
            MethodResolver resolver = MethodResolver.getInstance(elctx);

            for (String name : libs) {
                int sep = name.lastIndexOf('.');
                if (sep == -1) {
                    throw new ELException("Invalid import directive: " + name);
                }

                String clsname = name.substring(0, sep);
                name = name.substring(sep+1);
                Class cls = findClass(elctx, clsname);

                if (name.equals("*")) {
                    resolver.addGlobalMethods(cls);
                    for (Field field : cls.getFields()) {
                        importField(elctx, field, null);
                    }
                } else {
                    for (Method method : cls.getMethods()) {
                        if (Modifier.isStatic(method.getModifiers()) &&
                            name.equals(method.getName())) {
                            resolver.addGlobalMethod(method);
                        }
                    }
                    try {
                        importField(elctx, cls.getField(name), null);
                    } catch (NoSuchFieldException ex) {
                        // ignore
                    }
                }
            }
        }
    }

    private static void importField(ELContext elctx, Field field, String prefix) {
        if (Modifier.isStatic(field.getModifiers())) {
            try {
                field.setAccessible(true);
                String name = field.getName();
                if (prefix != null)
                    name = prefix + ":" + name;
                Closure closure;
                if (Modifier.isFinal(field.getModifiers())) {
                    closure = new LiteralClosure(field.get(null), true);
                } else {
                    closure = new FieldClosure(field);
                }
                elctx.getVariableMapper().setVariable(name, closure);
            } catch (IllegalAccessException ex) {
                // ignored
            }
        }
    }

    private void importPackages(ELContext elctx) {
        if (!imps.isEmpty()) {
            ClassResolver resolver = ClassResolver.getInstance(elctx);
            for (String imp : imps) {
                resolver.addImport(imp);
            }
        }
    }

    private static Class findClass(ELContext elctx, String name) {
        try {
            ClassLoader loader = Utils.getClassLoader(elctx);
            return Utils.findClass(name, loader);
        } catch (ClassNotFoundException ex) {
            throw new ELException(ex);
        }
    }
}
