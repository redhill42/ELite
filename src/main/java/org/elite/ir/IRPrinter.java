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

package org.elite.ir;

import elite.lang.Symbol;
import org.elite.parser.ELNode;

import java.lang.reflect.Method;

/**
 * Debugging utility: parses an ELite program and dumps the compiled IR.
 */
final class IRPrinter {

  private IRPrinter() {
  }

  static String dumpIR(IRFunction function) {
    return formatIR(function);
  }

  static String formatIR(IRFunction fn) {
    StringBuilder sb = new StringBuilder();
    sb.append(fn.name()).append(" params=").append(fn.paramCount())
      .append(" locals=").append(fn.maxLocals()).append(" blocks=")
      .append(fn.blockCount()).append(" words=").append(fn.code().length)
      .append("\n");

    DebugInfo di = fn.debugInfo();
    InstructionView v = new InstructionView(fn.code(), 0);
    while (v.inBounds()) {
      int blockId = fn.blockOfPc(v.offset());
      if (blockId != -1)
        sb.append("  B").append(blockId).append(":\n");
      int startIdx = sb.length();
      sb.append("    ").append(formatInst(v, fn.constantPool()));
      int line = di.lineForPC(v.offset());
      if (line != 0) {
        if (sb.length() - startIdx < 40)
          sb.append(" ".repeat(40 - (sb.length() - startIdx)));
        sb.append(" ; #").append(line);
      }
      sb.append("\n");
      v.advance();
    }

    return sb.toString();
  }

  @SuppressWarnings("unused")
  static String formatIR(IntList code, Object[] constants) {
    StringBuilder sb = new StringBuilder();
    InstructionView v = new InstructionView(code);
    while (v.inBounds()) {
      sb.append("    ").append(formatInst(v, constants)).append('\n');
      v.advance();
      ;
    }
    return sb.toString();
  }

  private static String formatInst(InstructionView v, Object[] constants) {
    int op = v.opcode();
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-14s", Opcode.name(op)));

    switch (op) {
    case Opcode.PUSH_VAR, Opcode.STORE_VAR, Opcode.STORE_VAR_POP ->
      sb.append(" v").append(v.varIndex());

    case Opcode.PUSH_CONST, Opcode.DEFINE_GLOBAL, Opcode.STORE_GLOBAL,
         Opcode.PUSH_GLOBAL, Opcode.INSTANCEOF, Opcode.CLOSURE,
         Opcode.INVOKE_DIRECT, Opcode.INVOKE_METHOD, Opcode.NEW,
         Opcode.CONSTRUCTOR, Opcode.GETFIELD, Opcode.PUTFIELD,
         Opcode.GETSTATIC, Opcode.PUTSTATIC, Opcode.CHECKCAST, Opcode.BOX,
         Opcode.UNBOX, Opcode.DECLARE_NS, Opcode.TRAMPOLINE ->
      formatConstPool(sb, constants, v.poolIndex());

    case Opcode.JUMP, Opcode.JUMP_IF_TRUE, Opcode.JUMP_IF_FALSE,
         Opcode.JUMP_IF_NULL, Opcode.JUMP_IF_NONNULL ->
      sb.append(" B").append(v.jumpTarget());

    case Opcode.NEW_ARRAY, Opcode.LOAD_ARRAY, Opcode.STORE_ARRAY -> {
      sb.append(" ").append(v.count()).append(",");
      formatConstPool(sb, constants, v.poolIndex());
    }

    case Opcode.TRY, Opcode.NEW_TUPLE ->
      sb.append(" ").append(v.count());
    }

    return sb.toString();
  }

  private static void formatConstPool(StringBuilder sb, Object[] constants,
                                      int idx) {
    sb.append(" #").append(idx);
    if (idx < constants.length) {
      Object val = constants[idx];
      sb.append(" ").append(formatConst(val));
    }
  }

  private static String formatConst(Object c) {
    if (c instanceof String s)
      return "\"" + s + "\"";
    if (c instanceof Number || c instanceof Boolean)
      return c.toString();
    if (c instanceof Symbol)
      return c.toString();
    if (c instanceof java.util.regex.Pattern)
      return '/' + c.toString() + '/';
    if (c instanceof IRFunction fn)
      return "<" + fn.name() + ">";
    if (c instanceof Class<?> cls)
      return "<" + cls.getName() + ">";
    if (c instanceof Method m)
      return "<" + m.getDeclaringClass().getSimpleName() + "." + m.getName() +
             ">";
    if (c instanceof ELNode n)
      return "<" + formatNode(n) + ">";
    return c.getClass().getSimpleName();
  }

  /**
   * Format a trampoline pool entry showing the AST node type and key info.
   */
  private static String formatNode(ELNode n) {
    StringBuilder sb = new StringBuilder();
    sb.append("<").append(n.getClass().getSimpleName());
    appendNodeDetails(sb, n);
    sb.append(">");
    return sb.toString();
  }

  /**
   * Append meaningful details for trampolined ELNode types.
   */
  private static void appendNodeDetails(StringBuilder sb, ELNode n) {
    if (n instanceof ELNode.DEFINE d)
      sb.append(" ").append(d.id);
    else if (n instanceof ELNode.IDENT id)
      sb.append(" ").append(id.id);
    else if (n instanceof ELNode.APPLY a) {
      if (a.right instanceof ELNode.ACCESS ac &&
          ac.index instanceof ELNode.IDENT idx)
        sb.append(" .").append(idx.id).append("(").append(a.args.length)
          .append(")");
      else if (a.right instanceof ELNode.IDENT fn)
        sb.append(" ").append(fn.id).append("(").append(a.args.length)
          .append(")");
    } else if (n instanceof ELNode.ACCESS ac) {
      if (ac.index instanceof ELNode.IDENT idx)
        sb.append(" .").append(idx.id);
    }
  }
}
