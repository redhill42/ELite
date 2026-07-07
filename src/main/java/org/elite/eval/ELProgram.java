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

package org.elite.eval;

import java.io.Serial;
import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import javax.el.ELContext;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import javax.el.ELException;

import elite.lang.Closure;
import org.elite.parser.ELNode;
import org.elite.parser.Position;
import org.elite.resolver.ClassResolver;
import org.elite.resolver.MethodResolver;
import org.elite.eval.closure.LiteralClosure;
import org.elite.eval.closure.FieldClosure;
import org.elite.ir.IRBuilder;
import org.elite.ir.IRBytecodeCompiler;
import org.elite.ir.IRFunction;
import org.elite.util.Utils;

public class ELProgram implements Serializable
{
    private final List<Module> mods;
    private final List<String> libs;
    private final List<String> imps;
    private final List<ELNode> defs;
    private final List<ELNode> exps;

    private String filename;
    private int startLine = 1;

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
    public static final int OPT_LEVEL = Integer.getInteger("elite.opt.level", 2);

    /** @return the list of definition statements */
    public List<ELNode> getDefinitions() { return defs; }
    /** @return the list of expression/statement nodes */
    public List<ELNode> getExpressions() { return exps; }

    @Serial
    private static final long serialVersionUID = 3112245719728771823L;

    public ELProgram() {
        this.mods = new ArrayList<>();
        this.libs = new ArrayList<>();
        this.imps = new ArrayList<>();
        this.defs = new ArrayList<>();
        this.exps = new ArrayList<>();
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
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

        // Import modules and classes to populate global context. The global
        // context is used by compilation and execution.
        importExternals(elctx);

        // Evaluate expressions in global context.
        EvaluationContext env = new EvaluationContext(elctx, fm, vm);
        Frame frame = StackTrace.addFrame(elctx, "__toplevel__", filename,
                                          Position.make(startLine, 1));

        try {
            // Define function and class for forward reference
            for (ELNode node : defs) {
                frame.setPos(node.pos);
                node.getValue(env);
            }

            Object result = null;
            for (ELNode node : exps) {
                frame.setPos(node.pos);
                result = node.getValue(env);
            }
            return result;
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    public IRFunction compile(ELContext elctx) {
        // Import modules and classes to populate global context. The global
        // context is used by compilation and execution.
        importExternals(elctx);
        return IRBuilder.compile(elctx, this);
    }

    public IRBytecodeCompiler.CompiledFunction compileToByteCode(ELContext elctx) {
        // Import modules and classes to populate global context. The global
        // context is used by compilation and execution.
        importExternals(elctx);
        return IRBytecodeCompiler.compile(compile(elctx));
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
            } else if (obj instanceof Module other) {
                return name.equals(other.name) &&
                       (Objects.equals(prefix, other.prefix));
            } else {
                return false;
            }
        }
    }

    public void importExternals(ELContext elctx) {
        importModules(elctx);
        importFunctions(elctx);
        importPackages(elctx);
    }

    private void importModules(ELContext elctx) {
        if (!mods.isEmpty()) {
            MethodResolver resolver = MethodResolver.getInstance(elctx);
            for (Module mod : mods) {
                Class<?> cls = findClass(elctx, mod.name);
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
                Class<?> cls = findClass(elctx, clsname);

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
                Utils.setAccessible(field);
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

    private static Class<?> findClass(ELContext elctx, String name) {
        try {
            ClassLoader loader = Utils.getClassLoader(elctx);
            return Utils.findClass(name, loader);
        } catch (ClassNotFoundException ex) {
            throw new ELException(ex);
        }
    }
}
