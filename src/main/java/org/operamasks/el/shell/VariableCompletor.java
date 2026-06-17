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

package org.operamasks.el.shell;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.beans.IntrospectionException;
import java.util.*;
import java.util.function.Consumer;
import javax.el.ELContext;
import javax.el.VariableMapper;
import javax.script.ScriptEngine;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.VariableMapperImpl;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.util.BeanUtils;
import org.operamasks.util.BeanProperty;

@SuppressWarnings("unchecked")
public class VariableCompletor implements Completer
{
    private final ELContext elctx;
    private final ScriptEngine engine;

    public VariableCompletor(ELContext elctx, ScriptEngine engine) {
        this.elctx = elctx;
        this.engine = engine;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line,
                         List<Candidate> candidates) {
        String word = line.word();
        int cursor = line.wordCursor();

        int dot = word.lastIndexOf('.');
        if (dot == -1) {
            String prefix = scanIdentifier(word, cursor, 0);
            if (prefix == null && word.substring(0, cursor).trim().isEmpty())
                prefix = "";
            if (prefix != null) {
                completeGlobals(prefix, candidates);
            }
        } else {
            String base = scanBase(word, dot, 0);
            String prefix = word.substring(dot+1, cursor);
            if (base != null && (prefix.isEmpty() || isIdentifier(prefix))) {
                completeMembers(base, prefix, candidates);
            }
        }
    }

    private void completeGlobals(String prefix, List<Candidate> candidates) {
        VariableMapper vm = elctx.getVariableMapper();
        if (vm instanceof VariableMapperImpl) {
            addCandidates(((VariableMapperImpl)vm).getVariableMap().keySet(), prefix, candidates);
        }

        MethodResolver resolver = MethodResolver.getInstance(elctx);
        addCandidates(resolver.listGlobalMethods(), prefix, candidates);
        addCandidates(resolver.listSystemMethods(), prefix, candidates);

        candidates.sort(Comparator.comparing(Candidate::value));
    }

    private void addCandidates(Collection<String> from, String prefix,
                               List<Candidate> candidates) {
        for (String name : from) {
            if (name.startsWith(prefix) && !contains(candidates, name)) {
                candidates.add(new Candidate(name));
            }
        }
    }

    private static boolean contains(List<Candidate> candidates, String value) {
        for (Candidate c : candidates) {
            if (c.value().equals(value)) return true;
        }
        return false;
    }

    private void completeMembers(String base, String prefix,
                                 List<Candidate> candidates) {
        Object value;
        try {
            value = engine.eval(base);
            if (value == null) return;
        } catch (Throwable ex) {
            return;
        }

        Set<String> seen = new HashSet<>();

        Consumer<String> addCandidate = (String name) -> candidates.add(
            new Candidate(base + "." + name, name, null, null, null, null, true, 0));

        if (value instanceof ClosureObject clo) {
            for (String name : clo.get_closures(elctx).keySet()) {
                if (name.startsWith(prefix) && seen.add(name)) {
                    addCandidate.accept(name);
                }
            }
        } else if (value instanceof Class<?> clazz) {
            for (Method method : clazz.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    String name = method.getName() + "()";
                    if (name.startsWith(prefix) && seen.add(name)) {
                        addCandidate.accept(name);
                    }
                }
            }

            for (Field field : clazz.getFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    String name = field.getName();
                    if (name.startsWith(prefix) && seen.add(name)) {
                        addCandidate.accept(name);
                    }
                }
            }
        } else {
            Class<?> clazz = value.getClass();

            for (Method method : clazz.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    String name = method.getName() + "()";
                    if (name.startsWith(prefix) && seen.add(name)) {
                        addCandidate.accept(name);
                    }
                }
            }

            for (Field field : clazz.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    String name = field.getName();
                    if (name.startsWith(prefix) && seen.add(name)) {
                        addCandidate.accept(name);
                    }
                }
            }

            if (!prefix.isEmpty()) {
                try {
                    for (BeanProperty p : BeanUtils.getProperties(clazz)) {
                        String name = p.getName();
                        if (name.startsWith(prefix) && seen.add(name)) {
                            addCandidate.accept(name);
                        }
                    }
                } catch (IntrospectionException ex) {
                    // ignored!
                }
            }
        }

        candidates.sort(Comparator.comparing(Candidate::value));
    }

    private static boolean isIdentifier(String str) {
        if (!str.isEmpty()) {
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
