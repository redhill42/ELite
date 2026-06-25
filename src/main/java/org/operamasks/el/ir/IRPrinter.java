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

import java.lang.reflect.Method;
import java.util.*;

import org.objectweb.asm.*;
import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

/**
 * Debugging utility: parses an ELite program and dumps the compiled IR.
 */
public final class IRPrinter {

    private IRPrinter() {}

    public static String dump(String source) {
        ELNode node = Parser.parseExpression(source);
        IRFunction fn = IRBuilder.compile(node);
        return format(fn, source);
    }

    /** Dump full program IR (definitions + expressions + combined). */
    public static String dumpProgramIR(String source) {
        Parser parser = new Parser(source);
        var program = parser.parse();

        LinkedHashSet<IRFunction> funcs = new LinkedHashSet<>();
        ArrayDeque<IRFunction> worklist = new ArrayDeque<>();
        IRFunction top = IRBuilder.compile(program);
        worklist.push(top);

        while (!worklist.isEmpty()) {
            IRFunction fn = worklist.pop();
            if (!funcs.contains(fn)) {
                funcs.add(fn);
                for (Object c : fn.constantPool()) {
                    if (c instanceof IRFunction)
                        worklist.push((IRFunction)c);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (IRFunction fn : funcs)
            sb.append(formatIR(fn));
        return sb.toString();
    }

    /** Dump JVM bytecode for a full program. */
    public static String dumpProgramBC(String source) {
        Parser parser = new Parser(source);
        var program = parser.parse();

        StringBuilder sb = new StringBuilder();
        sb.append("; bytecode\n");
        IRFunction fn = IRBuilder.compile(program);
        sb.append(dumpBytecode(fn));
        return sb.toString();
    }

    private static String format(IRFunction fn, String source) {
        return "; " + source + "\n" + formatIR(fn);
    }

    /** Dump JVM bytecode for an IRFunction (requires bytecode compiler). */
    public static String dumpBytecode(IRFunction fn) {
        try {
            IRBytecodeCompiler.CompiledFunction cf = IRBytecodeCompiler.compile(fn);
            return cf.bytecodeAsString();
        } catch (CompilationError e) {
            // Cannot compiler to byte code, silently return empty string
            return "";
        }
    }

    public static String formatIR(IRFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append(fn.name()).append(" params=").append(fn.paramCount())
          .append(" locals=").append(fn.maxLocalCount())
          .append(" blocks=").append(fn.blockCount())
          .append(" words=").append(fn.code().length)
          .append("\n");

        Object[] pool = fn.constantPool();
        if (pool.length > 0) {
            for (int i = 0; i < pool.length; i++) {
                sb.append("  #").append(i).append(" = ")
                  .append(formatConst(pool[i])).append("\n");
            }
        }

        Object[] vars = fn.varNames();
        for (int i = 0; i < vars.length; i++) {
            sb.append("  v").append(i).append(" = ").append(vars[i]).append("\n");
        }

        for (int b = 0; b < fn.blockCount(); b++) {
            int start = fn.blockStart(b);
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : fn.code().length;
            sb.append("  B").append(b).append(":\n");

            InstructionView v = new InstructionView(fn.code(), start, fn.constantPool());
            while (v.inBounds() && v.offset() < end) {
                sb.append("    ").append(formatInst(v, fn)).append("\n");
                v.advance();
            }
        }
        return sb.toString();
    }

    private static void formatConstPool(StringBuilder sb, IRFunction fn, int idx) {
        sb.append(" #").append(idx);
        if (idx < fn.constantPool().length) {
            Object val = fn.constantPool()[idx];
            if (val instanceof IRFunction irf)
                sb.append(" '").append(irf.name()).append("'");
            else if (val instanceof Method m)
                sb.append(" '").append(m.getName()).append("'");
            else
                sb.append(" '").append(val).append("'");
        }
    }

    private static String formatInst(InstructionView v, IRFunction fn) {
        int op = v.opcode();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-14s", Opcode.name(op)));

        switch (op) {
            case Opcode.PUSH_CONST -> {
                int idx = v.constPoolIndex();
                sb.append(" #").append(idx);
                if (idx < fn.constantPool().length) {
                    sb.append(" ").append(formatConst(fn.constantPool()[idx]));
                }
            }
            case Opcode.PUSH_VAR -> sb.append(" v").append(v.varIndex());
            case Opcode.STORE_VAR -> sb.append(" v").append(v.payload() & 0xFFFF);
            case Opcode.PUSH_GLOBAL -> formatConstPool(sb, fn, v.constPoolIndex());
            case Opcode.DEFINE_GLOBAL, Opcode.STORE_GLOBAL -> formatConstPool(sb, fn, v.payload());
            case Opcode.JUMP, Opcode.JUMP_IF_TRUE, Opcode.JUMP_IF_FALSE,
                 Opcode.JUMP_IF_NULL, Opcode.JUMP_IF_NONNULL ->
                sb.append(" B").append(v.jumpTarget());
            case Opcode.INVOKE_DYN, Opcode.INVOKE_TAIL ->
                sb.append(" ").append(v.payload());
            case Opcode.INVOKE_DIRECT, Opcode.INVOKE_TARGET, Opcode.INVOKE_METHOD
                -> formatConstPool(sb, fn, v.payload());
            case Opcode.CLOSURE, Opcode.DELAY -> {
                formatConstPool(sb, fn, v.payload());
                int captureCount = v.opCount() > 0 ? v.operand(0) : 0;
                sb.append(" capture=").append(captureCount);
            }
            case Opcode.NEW_MAP, Opcode.NEW_TUPLE ->
                sb.append(" ").append(v.payload());
            case Opcode.TRAMPOLINE -> {
                int idx = v.constPoolIndex();
                sb.append(" #").append(idx);
                if (idx < fn.constantPool().length) {
                    sb.append(" ").append(formatTrampolineNode(fn.constantPool()[idx]));
                }
            }
        }
        return sb.toString();
    }

    /** Format a trampoline pool entry showing the AST node type and key info. */
    private static String formatTrampolineNode(Object c) {
        if (c instanceof ELNode n) {
            StringBuilder sb = new StringBuilder();
            sb.append("<").append(n.getClass().getSimpleName());
            appendNodeDetails(sb, n);
            sb.append(">");
            return sb.toString();
        }
        return formatConst(c);
    }

    /** Append meaningful details for trampolined ELNode types. */
    private static void appendNodeDetails(StringBuilder sb, ELNode n) {
        if (n instanceof org.operamasks.el.parser.ELNode.DEFINE d)
            sb.append(" ").append(d.id);
        else if (n instanceof org.operamasks.el.parser.ELNode.IDENT id)
            sb.append(" ").append(id.id);
        else if (n instanceof org.operamasks.el.parser.ELNode.APPLY a) {
            if (a.right instanceof org.operamasks.el.parser.ELNode.ACCESS ac
                && ac.index instanceof org.operamasks.el.parser.ELNode.IDENT idx)
                sb.append(" .").append(idx.id).append("(").append(a.args.length).append(")");
            else if (a.right instanceof org.operamasks.el.parser.ELNode.IDENT fn)
                sb.append(" ").append(fn.id).append("(").append(a.args.length).append(")");
        } else if (n instanceof org.operamasks.el.parser.ELNode.ACCESS ac) {
            if (ac.index instanceof org.operamasks.el.parser.ELNode.IDENT idx)
                sb.append(" .").append(idx.id);
        } else if (n instanceof org.operamasks.el.parser.ELNode.COND)
            sb.append(" ?:");
    }

    private static String formatConst(Object c) {
        if (c instanceof String s) return "\"" + s + "\"";
        if (c instanceof Number || c instanceof Boolean) return c.toString();
        if (c instanceof IRFunction fn) return "<IRFunction " + fn.name() + ">";
        if (c instanceof Method m) return "<Method " + m.getName() + ">";
        if (c instanceof ELNode n) return "<" + n.getClass().getSimpleName() + ">";
        return c.getClass().getSimpleName();
    }

    /** If the node is a DEFINE wrapping a LAMBDA, dump the lambda body IR. */
    private static void dumpLambdaBody(StringBuilder sb, ELNode node) {
        if (node instanceof org.operamasks.el.parser.ELNode.DEFINE def
            && def.expr instanceof org.operamasks.el.parser.ELNode.LAMBDA lambda) {

            String name = lambda.name != null ? lambda.name : def.id;
            String[] params = new String[lambda.vars.length];
            for (int i = 0; i < lambda.vars.length; i++) {
                params[i] = lambda.vars[i].id;
            }
            IRFunction bodyIR = IRBuilder.compileLambda(name, params, lambda.body);
            sb.append("  [lambda body]\n");
            sb.append(indent(formatIR(bodyIR), "  "));
        }
    }

    private static String indent(String s, String prefix) {
        return prefix + s.replace("\n", "\n" + prefix);
    }

    private static String nodeName(ELNode node) {
        if (node == null) return "null";
        return switch (node.op) {
            case org.operamasks.el.parser.Token.DEFINE ->
                "DEFINE " + ((org.operamasks.el.parser.ELNode.DEFINE) node).id;
            case org.operamasks.el.parser.Token.LAMBDA -> "LAMBDA";
            case org.operamasks.el.parser.Token.IDENT ->
                "IDENT " + ((org.operamasks.el.parser.ELNode.IDENT) node).id;
            default -> node.getClass().getSimpleName();
        };
    }
}
