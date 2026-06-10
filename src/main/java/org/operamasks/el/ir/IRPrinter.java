package org.operamasks.el.ir;

import java.util.List;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Parser;

/**
 * Debugging utility: parses an ELite program and dumps the compiled IR.
 *
 * Usage: {@code IRPrinter.dump("1 + 2 * 3")} or {@code IRPrinter.dumpFile("test.xel")}.
 */
public final class IRPrinter {

    private IRPrinter() {}

    /** Parse and dump IR for a single expression string. */
    public static String dump(String source) {
        ELNode node = Parser.parseExpression(source);
        IRFunction fn = IRBuilder.compile(node);
        return format(fn, source);
    }

    /** Parse and dump IR for an entire program. */
    public static String dumpProgram(String source) {
        Parser parser = new Parser(source);
        var program = parser.parse();
        List<ELNode> defs = program.getDefinitions();
        List<ELNode> exps = program.getExpressions();

        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║         ELite IR Dump                    ║\n");
        sb.append("╠══════════════════════════════════════════╣\n");

        // Dump definitions
        if (!defs.isEmpty()) {
            sb.append("║ >>> DEFINITIONS <<<                      ║\n");
            for (int i = 0; i < defs.size(); i++) {
                ELNode def = defs.get(i);
                sb.append("║ Definition ").append(i + 1).append(":\n");
                try {
                    IRFunction fn = IRBuilder.compile(def);
                    sb.append(indent(formatIR(fn), "  "));
                } catch (Exception e) {
                    sb.append("  [IR compile failed: ").append(e.getMessage()).append("]\n");
                }
            }
        }

        // Dump expressions
        if (!exps.isEmpty()) {
            sb.append("║ >>> EXPRESSIONS <<<                      ║\n");
            for (int i = 0; i < exps.size(); i++) {
                ELNode exp = exps.get(i);
                String desc = nodeDescription(exp);
                sb.append("║ Expression ").append(i + 1).append(": ")
                  .append(truncate(desc, 60)).append("\n");
                try {
                    IRFunction fn = IRBuilder.compile(exp);
                    sb.append(indent(formatIR(fn), "  "));
                } catch (Exception e) {
                    sb.append("  [IR compile failed: ").append(e.getMessage()).append("]\n");
                }
                if (i < exps.size() - 1) sb.append("\n");
            }
        }

        // Combined IR (all expressions)
        if (!exps.isEmpty()) {
            sb.append("║ >>> COMBINED (all expressions) <<<       ║\n");
            try {
                IRFunction fn = IRBuilder.compile(exps);
                sb.append(indent(formatIR(fn), "  "));
            } catch (Exception e) {
                sb.append("  [IR compile failed: ").append(e.getMessage()).append("]\n");
            }
        }

        sb.append("╚══════════════════════════════════════════╝\n");
        return sb.toString();
    }

    // ── Formatting ──

    private static String format(IRFunction fn, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== IR for: ").append(source).append(" ===\n");
        sb.append(formatIR(fn));
        return sb.toString();
    }

    /** Pretty-print an IR function. */
    public static String formatIR(IRFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("Function: ").append(fn.name())
          .append("  params=").append(fn.paramCount())
          .append("  blocks=").append(fn.blockCount())
          .append("  codeWords=").append(fn.code().length)
          .append("  poolSize=").append(fn.constantPool().length)
          .append("\n");

        // Dump constant pool
        Object[] pool = fn.constantPool();
        if (pool.length > 0) {
            sb.append("  Constant pool:\n");
            for (int i = 0; i < pool.length; i++) {
                Object c = pool[i];
                String type = c == null ? "null" : c.getClass().getSimpleName();
                String val = c == null ? "null" : formatConstant(c);
                sb.append("    #").append(i).append(" = ").append(val)
                  .append("  (").append(type).append(")\n");
            }
        }

        // Dump blocks
        for (int b = 0; b < fn.blockCount(); b++) {
            int start = fn.blockStart(b);
            int end = (b + 1 < fn.blockCount()) ? fn.blockStart(b + 1) : fn.code().length;
            sb.append("  B").append(b).append(" [offset=").append(start)
              .append(" size=").append(end - start).append(" words]:\n");

            InstructionView v = new InstructionView(fn.code(), start);
            while (v.inBounds() && v.offset() < end) {
                sb.append("    ").append(formatInstruction(v, fn)).append("\n");
                v.advance();
            }
        }
        return sb.toString();
    }

    /** Format a single instruction with decoded metadata. */
    private static String formatInstruction(InstructionView v, IRFunction fn) {
        int op = v.opcode();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("@%-4d ", v.offset()));
        sb.append(String.format("%-16s", Opcode.name(op)));

        // Show type info (only for ops that encode type in payload)
        int kind = v.kind();
        boolean typeInPayload = (op == Opcode.IADD || op == Opcode.DADD || op == Opcode.LADD
            || Opcode.isComparison(op) || op == Opcode.RETURN);
        if (typeInPayload && kind == IRFormat.K_PRIM) {
            sb.append(" t:").append(IRFormat.primTypeName(v.payload()));
        } else if (!typeInPayload && kind == IRFormat.K_PRIM) {
            sb.append(" t:prim");
        } else if (kind == IRFormat.K_DYN) {
            sb.append(" t:dyn");
        } else if (kind == IRFormat.K_BOOL) {
            sb.append(" t:bool");
        } else if (kind == IRFormat.K_GUARDED) {
            sb.append(" guard:").append(IRFormat.primTypeName(v.payload()));
        }

        // Show operands
        switch (op) {
            case Opcode.PUSH_CONST -> {
                int idx = v.constPoolIndex();
                sb.append(" pool#").append(idx);
                if (idx < fn.constantPool().length) {
                    sb.append("=").append(formatConstant(fn.constantPool()[idx]));
                }
            }
            case Opcode.PUSH_VAR -> sb.append(" v").append(v.varIndex());
            case Opcode.PUSH_GLOBAL, Opcode.PUSH_GLOBAL_N -> {
                int idx = v.constPoolIndex();
                sb.append(" name#").append(idx);
                if (idx < fn.constantPool().length) {
                    sb.append("='").append(fn.constantPool()[idx]).append("'");
                }
            }
            case Opcode.STORE_VAR -> sb.append(" v").append(v.payload() & 0xFFFF);
            case Opcode.STORE_GLOBAL -> {
                int idx = v.payload();
                sb.append(" name#").append(idx);
                if (idx < fn.constantPool().length) {
                    sb.append("='").append(fn.constantPool()[idx]).append("'");
                }
            }
            case Opcode.JUMP, Opcode.JUMP_IF_TRUE, Opcode.JUMP_IF_FALSE,
                 Opcode.JUMP_IF_NULL, Opcode.JUMP_IF_NONNULL ->
                sb.append(" →B").append(v.jumpTarget());
            case Opcode.INVOKE_DYN, Opcode.INVOKE_TAIL ->
                sb.append(" argc=").append(v.payload());
            case Opcode.RETURN -> {
                int tid = v.primTypeId();
                if (tid >= 0) sb.append(" t:").append(IRFormat.primTypeName(tid));
            }
            case Opcode.NEW_LIST, Opcode.NEW_MAP, Opcode.NEW_TUPLE ->
                sb.append(" count=").append(v.payload());
        }
        return sb.toString();
    }

    private static String formatConstant(Object c) {
        if (c instanceof String s) {
            if (s.length() > 40) s = s.substring(0, 37) + "...";
            return "\"" + s + "\"";
        }
        if (c instanceof Number || c instanceof Boolean) return c.toString();
        if (c instanceof IRFunction fn) return "<IRFunction " + fn.name() + ">";
        if (c instanceof ELNode) return "<ELNode " + Opcode.name(((ELNode)c).op) + ">";
        return c.getClass().getSimpleName();
    }

    private static String indent(String s, String prefix) {
        return prefix + s.replace("\n", "\n" + prefix);
    }

    private static String nodeDescription(ELNode node) {
        if (node == null) return "null";
        String type = node.getClass().getSimpleName();
        return switch (node.op) {
            case org.operamasks.el.parser.Token.DEFINE ->
                type + " " + ((org.operamasks.el.parser.ELNode.DEFINE) node).id + " = ...";
            case org.operamasks.el.parser.Token.LAMBDA ->
                type + " " + ((org.operamasks.el.parser.ELNode.LAMBDA) node).name;
            case org.operamasks.el.parser.Token.IDENT ->
                type + "(" + ((org.operamasks.el.parser.ELNode.IDENT) node).id + ")";
            case org.operamasks.el.parser.Token.APPLY ->
                type + "(args=" + ((org.operamasks.el.parser.ELNode.APPLY) node).args.length + ")";
            default -> type;
        };
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
