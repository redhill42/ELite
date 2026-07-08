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

package org.elite.parser;

import org.elite.eval.ELProgram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Pretty-prints an ELNode AST as a tree for debugging.
 */
public final class ASTDumper {

    private ASTDumper() {}

    /** Dump a single ELNode tree. */
    public static String dump(ELNode node) {
        StringBuilder sb = new StringBuilder();
        dumpNode(node, sb, "", true, true);
        return sb.toString();
    }

    public static String dump(ELProgram program) {
        StringBuilder sb = new StringBuilder();
        for (ELNode node : program.getDefinitions()) {
            dumpNode(node, sb, "", true, true);
            sb.append('\n');
        }
        for (ELNode node : program.getExpressions()) {
            dumpNode(node, sb, "", true, true);
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── Internal tree printer ──

    private static void dumpNode(ELNode node, StringBuilder sb,
                                  String prefix, boolean isLast, boolean isRoot) {
        if (node == null) {
            sb.append(prefix).append(isLast ? "└─ " : "├─ ")
              .append("(null)\n");
            return;
        }

        String connector = isRoot ? "" : (isLast ? "└─ " : "├─ ");
        sb.append(prefix).append(connector);

        // Node header: type name + key values
        String typeName = node.getClass().getSimpleName();
        sb.append(typeName);

        // Append key inline fields
        appendInlineFields(node, sb);

        sb.append('\n');

        // Collect child nodes
        Child[] children = collectChildren(node);
        String childPrefix = prefix + (isRoot ? "" : (isLast ? "   " : "│  "));

        for (int i = 0; i < children.length; i++) {
            Child c = children[i];
            boolean last = (i == children.length - 1);
            if (c.node != null) {
                dumpNode(c.node, sb, childPrefix, last, false);
            } else if (c.nodes != null) {
                dumpNodeList(c.nodes, sb, childPrefix, last, c.label);
            } else if (c.value != null) {
                // Leaf field with a non-node value
                sb.append(childPrefix).append(last ? "└─ " : "├─ ")
                  .append(c.label).append(": ").append(c.value).append('\n');
            }
        }
    }

    private static void dumpNodeList(ELNode[] nodes, StringBuilder sb,
                                      String prefix, boolean isLast,
                                      String label) {
        String connector = isLast ? "└─ " : "├─ ";
        if (nodes.length == 0) {
            sb.append(prefix).append(connector).append(label).append(": []\n");
            return;
        }
        sb.append(prefix).append(connector).append(label);
        sb.append(":\n");
        String childPrefix = prefix + (isLast ? "   " : "│  ");
        for (int i = 0; i < nodes.length; i++) {
            dumpNode(nodes[i], sb, childPrefix, i == nodes.length - 1, false);
        }
    }

    // ── Inline fields ──

    private static void appendInlineFields(ELNode node, StringBuilder sb) {
        Class<?> cls = node.getClass();
        // id field (IDENT, DEFINE, CLASSDEF, etc.)
        try {
            Field idField = cls.getField("id");
            Object id = idField.get(node);
            if (id != null) sb.append(" '").append(id).append("'");
        } catch (NoSuchFieldException | IllegalAccessException e) { /* skip */ }

        // name field (LAMBDA, APPLY)
        try {
            Field nameField = cls.getField("name");
            Object nm = nameField.get(node);
            if (nm != null) sb.append(" name='").append(nm).append("'");
        } catch (NoSuchFieldException | IllegalAccessException e) { /* skip */ }

        // type annotation
        try {
            Field typeField = cls.getField("type");
            Object type = typeField.get(node);
            if (type != null) sb.append(" :").append(type);
        } catch (NoSuchFieldException | IllegalAccessException e) { /* skip */ }

        if (node.scope != null) {
            sb.append(" scope(depth=").append(node.scope.depth()).append(")");
        }

        if (node.symbol != null) {
            if (node.symbol.captured)
                sb.append(" captured");
            else
                sb.append(" slot=").append(node.symbol.slot);
        }

        // Literal values
        if (node instanceof ELNode.NUMBER n) {
            sb.append(" ").append(n.value);
        } else if (node instanceof ELNode.STRINGVAL s) {
            sb.append(" \"").append(escapeStr(s.value)).append("\"");
        } else if (node instanceof ELNode.CHARVAL c) {
            sb.append(" '").append(escapeStr(String.valueOf(c.value))).append("'");
        } else if (node instanceof ELNode.BOOLEANVAL b) {
            sb.append(" ").append(b.value);
        } else if (node instanceof ELNode.SYMBOL s) {
            sb.append(" '").append(s.value).append("'");
        } else if (node instanceof ELNode.LITERAL l) {
            sb.append(" ").append(l.value);
        } else if (node instanceof ELNode.CONS cons && cons.delay) {
            sb.append(" ").append("delay");
        }
    }

    // ── Child collection ──

    private static class Child {
        String label;
        ELNode node;          // single node child
        ELNode[] nodes;       // multiple node children
        String value;         // non-node leaf value
    }

    private static Child[] collectChildren(ELNode node) {
        List<Child> result = new java.util.ArrayList<>();

        // Try common field names by reflection
        Class<?> cls = node.getClass();
        while (cls != ELNode.class && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod))
                    continue;

                switch (f.getName()) {
                case "op", "id", "type", "value", "file", "pos", "name", "dvals", "varargs",
                     "negative", "exclude", "readonly", "immediate", "delay", "rt" -> {
                    continue;
                }
                }

                if (f.getName().startsWith("this$") || f.getName().startsWith("__"))
                    continue; // inner class ref

                f.setAccessible(true);
                try {
                    Object val = f.get(node);
                    if (val == null)
                        continue;

                    Child c = new Child();
                    c.label = f.getName();

                    if (val instanceof ELNode childNode) {
                        c.node = childNode;
                    } else if (val instanceof ELNode[] childNodes) {
                        c.nodes = childNodes;
                    } else if (val instanceof ELNode.Pattern[] pats) {
                        ELNode[] nodes = new ELNode[pats.length];
                        for (int i = 0; i < pats.length; i++)
                            nodes[i] = (ELNode)pats[i];
                        c.nodes = nodes;
                    } else if (val instanceof String || val instanceof Number
                               || val instanceof Boolean) {
                        c.value = String.valueOf(val);
                    } else if (val instanceof String[] sa) {
                        c.value = java.util.Arrays.toString(sa);
                    } else {
                        // Skip complex non-node objects (e.g., METASET, Operator)
                        continue;
                    }
                    result.add(c);
                } catch (IllegalAccessException e) { /* skip */ }
            }
            cls = cls.getSuperclass();
        }
        return result.toArray(new Child[0]);
    }

    private static String escapeStr(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                default:   sb.append(ch);
            }
        }
        return sb.toString();
    }
}
