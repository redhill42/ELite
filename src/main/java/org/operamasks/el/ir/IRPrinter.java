package org.operamasks.el.ir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

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

    public static String dumpProgram(String source) {
        Parser parser = new Parser(source);
        var program = parser.parse();
        List<ELNode> defs = program.getDefinitions();
        List<ELNode> exps = program.getExpressions();

        StringBuilder sb = new StringBuilder();

        if (!defs.isEmpty()) {
            for (int i = 0; i < defs.size(); i++) {
                ELNode def = defs.get(i);
                sb.append("; definition ").append(nodeName(def)).append("\n");
                try {
                    IRFunction fn = IRBuilder.compile(def);
                    sb.append(formatIR(fn));
                    // If the definition wraps a lambda, also dump the lambda body IR
                    dumpLambdaBody(sb, def);
                } catch (Exception e) {
                    sb.append("  [compile failed: ").append(e.getMessage()).append("]\n");
                }
            }
        }

        if (!exps.isEmpty()) {
            for (int i = 0; i < exps.size(); i++) {
                ELNode exp = exps.get(i);
                sb.append("; expression ").append(nodeName(exp)).append("\n");
                try {
                    IRFunction fn = IRBuilder.compile(exp);
                    sb.append(formatIR(fn));
                } catch (Exception e) {
                    sb.append("  [compile failed: ").append(e.getMessage()).append("]\n");
                }
            }
        }

        if (!exps.isEmpty()) {
            sb.append("; combined\n");
            try {
                IRFunction fn = IRBuilder.compileWithDefs(defs, exps);
                sb.append(formatIR(fn));
                sb.append(dumpBytecode(fn));
            } catch (Exception e) {
                sb.append("  [compile failed: ").append(e.getMessage()).append("]\n");
            }
        }

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
        } catch (Exception e) {
            return "[bytecode compile failed: " + e.getMessage() + "]";
        }
    }

    public static String formatIR(IRFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append(fn.name()).append(" params=").append(fn.paramCount())
          .append(" blocks=").append(fn.blockCount())
          .append(" words=").append(fn.code().length)
          .append("\n");

        Object[] pool = fn.constantPool();
        if (pool.length > 0) {
            for (int i = 0; i < pool.length; i++) {
                sb.append("  #").append(i).append(" = ").append(formatConst(pool[i])).append("\n");
            }
        }

        for (int b = 0; b < fn.blockCount(); b++) {
            int start = fn.blockStart(b);
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : fn.code().length;
            sb.append("  B").append(b).append(":\n");

            InstructionView v = new InstructionView(fn.code(), start);
            while (v.inBounds() && v.offset() < end) {
                sb.append("    ").append(formatInst(v, fn)).append("\n");
                v.advance();
            }
        }
        return sb.toString();
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
            case Opcode.PUSH_GLOBAL -> {
                int idx = v.constPoolIndex();
                sb.append(" #").append(idx);
                if (idx < fn.constantPool().length) {
                    sb.append(" '").append(fn.constantPool()[idx]).append("'");
                }
            }
            case Opcode.STORE_VAR -> sb.append(" v").append(v.payload() & 0xFFFF);
            case Opcode.STORE_GLOBAL -> {
                int idx = v.payload();
                sb.append(" #").append(idx);
                if (idx < fn.constantPool().length) {
                    sb.append(" '").append(fn.constantPool()[idx]).append("'");
                }
            }
            case Opcode.JUMP, Opcode.JUMP_IF_TRUE, Opcode.JUMP_IF_FALSE,
                 Opcode.JUMP_IF_NULL, Opcode.JUMP_IF_NONNULL ->
                sb.append(" B").append(v.jumpTarget());
            case Opcode.INVOKE_DYN, Opcode.INVOKE_TAIL ->
                sb.append(" ").append(v.payload());
            case Opcode.NEW_LIST, Opcode.NEW_MAP, Opcode.NEW_TUPLE ->
                sb.append(" ").append(v.payload());
        }
        return sb.toString();
    }

    private static String formatConst(Object c) {
        if (c instanceof String s) return "\"" + s + "\"";
        if (c instanceof Number || c instanceof Boolean) return c.toString();
        if (c instanceof IRFunction fn) return "<IRFunction " + fn.name() + ">";
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
