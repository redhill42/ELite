/*
 * Copyright (c) 2006-2011 Daniel Yuan.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
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
import org.operamasks.util.Utils;

public class ELProgram implements Serializable
{
    private List<Module> mods;
    private List<String> libs;
    private List<String> imps;
    private List<ELNode> defs;
    private List<ELNode> exps;

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

            // 3) execute statements
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
                Class cls = findClass(mod.name);
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
                Class cls = findClass(clsname);

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

    private static Class findClass(String name) {
        try {
            return Utils.findClass(name);
        } catch (ClassNotFoundException ex) {
            throw new ELException(ex);
        }
    }
}
