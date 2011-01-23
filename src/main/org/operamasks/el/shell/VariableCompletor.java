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

package org.operamasks.el.shell;

import java.util.List;
import java.util.Collections;
import java.util.Collection;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.beans.IntrospectionException;
import javax.el.ELContext;
import javax.el.VariableMapper;
import javax.script.ScriptEngine;

import jline.Completor;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.VariableMapperImpl;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.util.BeanUtils;
import org.operamasks.util.BeanProperty;

@SuppressWarnings("unchecked")
public class VariableCompletor implements Completor
{
    private ELContext elctx;
    private ScriptEngine engine;

    public VariableCompletor(ELContext elctx, ScriptEngine engine) {
        this.elctx = elctx;
        this.engine = engine;
    }

    public int complete(String buffer, int cursor, List candidates) {
        int dot = buffer.lastIndexOf('.', cursor);
        if (dot == -1) {
            String prefix = scanIdentifier(buffer, cursor, 0);
            if (prefix == null && buffer.substring(0,cursor).trim().length() == 0)
                prefix = "";
            if (prefix != null) {
                completeGlobals(prefix, candidates);
                return cursor - prefix.length();
            }
        } else {
            String base = scanBase(buffer, dot, 0);
            String prefix = buffer.substring(dot+1, cursor);
            if (base != null && (prefix.length() == 0 || isIdentifier(prefix))) {
                if (completeMembers(base, prefix, candidates)) {
                    return dot+1;
                }
            }
        }
        return cursor;
    }

    private void completeGlobals(String prefix, List candidates) {
        VariableMapper vm = elctx.getVariableMapper();
        if (vm instanceof VariableMapperImpl) {
            addCandidates(((VariableMapperImpl)vm).getVariableMap().keySet(), prefix, candidates);
        }

        MethodResolver resolver = MethodResolver.getInstance(elctx);
        addCandidates(resolver.listGlobalMethods(), prefix, candidates);
        addCandidates(resolver.listSystemMethods(), prefix, candidates);

        Collections.sort(candidates);
    }

    private void addCandidates(Collection<String> from, String prefix, List candidates) {
        for (String name : from) {
            if (name.startsWith(prefix) && !candidates.contains(name)) {
                candidates.add(name);
            }
        }
    }

    private boolean completeMembers(String base, String prefix, List candidates) {
        Object value;
        try {
            value = engine.eval(base);
            if (value == null) return false;
        } catch (Throwable ex) {
            return false;
        }

        if (value instanceof ClosureObject) {
            ClosureObject clo = (ClosureObject)value;
            for (String name : clo.get_closures(elctx).keySet()) {
                if (name.startsWith(prefix) && !candidates.contains(name)) {
                    candidates.add(name);
                }
            }
        } else if (value instanceof Class) {
            Class clazz = (Class)value;

            for (Method method : clazz.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    String name = method.getName() + "()";
                    if (name.startsWith(prefix) && !candidates.contains(name)) {
                        candidates.add(name);
                    }
                }
            }

            for (Field field : clazz.getFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    String name = field.getName();
                    if (name.startsWith(prefix) && !candidates.contains(name)) {
                        candidates.add(name);
                    }
                }
            }
        } else {
            Class clazz = value.getClass();

            for (Method method : clazz.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    String name = method.getName() + "()";
                    if (name.startsWith(prefix) && !candidates.contains(name)) {
                        candidates.add(name);
                    }
                }
            }

            for (Field field : clazz.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    String name = field.getName();
                    if (name.startsWith(prefix) && !candidates.contains(name)) {
                        candidates.add(name);
                    }
                }
            }

            if (prefix.length() != 0) {
                try {
                    for (BeanProperty p : BeanUtils.getProperties(clazz)) {
                        String name = p.getName();
                        if (name.startsWith(prefix) && !candidates.contains(name)) {
                            candidates.add(name);
                        }
                    }
                } catch (IntrospectionException ex) {
                    // ignored!
                }
            }
        }

        Collections.sort(candidates);
        return true;
    }

    private static boolean isIdentifier(String str) {
        if (str.length() > 0) {
            if (isIdentifierStart(str.charAt(0))) {
                for (int i = 1; i < str.length(); i++) {
                    if (!isIdentifierPart(str.charAt(i))) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static String scanIdentifier(String str, int from, int to) {
        int start = -1;
        for (int i = from; --i >= to; ) {
            char ch = str.charAt(i);
            if (isIdentifierStart(ch)) {
                start = i;
            } else if (!isIdentifierPart(ch)) {
                break;
            }
        }
        return start == -1 ? null : str.substring(start, from);
    }

    private static String scanBase(String str, int from, int to) {
        int start = -1, paren = 0;
        for (int i = from; --i >= to; ) {
            char ch = str.charAt(i);
            if (paren == 0) {
                if (isIdentifierStart(ch)) {
                    start = i;
                } else if (ch == ')') {
                    paren = 1;
                } else if (!isIdentifierPart(ch) && ch != '.') {
                    break;
                }
            } else {
                if (ch == ')') {
                    paren++;
                } else if (ch == '(') {
                    paren--;
                    if (paren == 0) {
                        start = i;
                    } else if (paren < 0) {
                        break;
                    }
                }
            }
        }
        return start == -1 ? null : str.substring(start, from);
    }

    private static boolean isIdentifierStart(char ch) {
        return Character.isJavaIdentifierStart(ch) || ch == '@';
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isJavaIdentifierPart(ch) || ch == '@' || ch == ':';
    }
}
