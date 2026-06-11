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

import java.util.*;
import javax.el.ELContext;
import javax.el.VariableMapper;
import javax.script.ScriptEngine;
import org.operamasks.el.resolver.MethodResolver;

/**
 * TAB completion for ELite REPL.
 * Matches known variables and global methods against the prefix before cursor.
 */
class ELiteCompletor implements ConsoleReader.Completor {

    private final ELContext elctx;
    private final ScriptEngine engine;

    ELiteCompletor(ELContext elctx, ScriptEngine engine) {
        this.elctx = elctx;
        this.engine = engine;
    }

    @Override
    public List<String> complete(String line, int cursor) {
        String prefix = extractPrefix(line, cursor);
        if (prefix.isEmpty()) return Collections.emptyList();

        List<String> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Variables from ELContext
        VariableMapper vm = elctx.getVariableMapper();
        if (vm != null) {
            for (String var : listVariableNames(vm)) {
                if (var.startsWith(prefix) && seen.add(var))
                    matches.add(var);
            }
        }

        // Global methods
        MethodResolver resolver = MethodResolver.getInstance(elctx);
        for (String method : resolver.listGlobalMethods()) {
            if (method.startsWith(prefix) && seen.add(method))
                matches.add(method);
        }
        for (String method : resolver.listSystemMethods()) {
            if (method.startsWith(prefix) && seen.add(method))
                matches.add(method);
        }

        Collections.sort(matches);
        return matches;
    }

    private static String extractPrefix(String line, int cursor) {
        int start = cursor;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1)))
            start--;
        return line.substring(start, cursor);
    }

    /** Work around VariableMapper not having a keys() method. */
    private static Set<String> listVariableNames(VariableMapper vm) {
        Set<String> names = new HashSet<>();
        // Try known implementation
        if (vm instanceof org.operamasks.el.eval.VariableMapperImpl) {
            names.addAll(((org.operamasks.el.eval.VariableMapperImpl) vm).getVariableMap().keySet());
        }
        // Also check script engine bindings
        return names;
    }
}
