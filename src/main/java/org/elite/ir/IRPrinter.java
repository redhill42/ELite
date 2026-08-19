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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Debugging utility: parses an ELite program and dumps the compiled IR.
 */
final class IRPrinter {

  private IRPrinter() {
  }

  static String dumpIR(IRProgram program) {
    StringBuilder sb = new StringBuilder();
    for (IRFunction fn : program.functions())
      sb.append(dumpIR(fn));
    for (IRClass cls : program.classes())
      sb.append(dumpIR(cls));
    return sb.toString();
  }

  static String dumpIR(IRFunction fn) {
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

  static String dumpIR(IRClass cls) {
    StringBuilder sb = new StringBuilder();
    sb.append("class ").append(cls.internalName).append("\n");
    if (cls.clinit_proc != null)
      sb.append(cls.name).append('.').append(dumpIR(cls.clinit_proc.symbol.func));
    if (cls.init_proc != null)
      sb.append(cls.name).append('.').append(dumpIR(cls.init_proc.symbol.func));
    for (IRFunction fn : cls.functions())
      sb.append(cls.name).append('.').append(dumpIR(fn));
    return sb.toString();
  }

  @SuppressWarnings("unused")
  private static String formatIR(IntList code, Object[] constants) {
    StringBuilder sb = new StringBuilder();
    InstructionView v = new InstructionView(code);
    while (v.inBounds()) {
      sb.append("    ").append(formatInst(v, constants)).append('\n');
      v.advance();
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
         Opcode.INVOKE_DIRECT, Opcode.INVOKE_DYNAMIC, Opcode.INVOKE_METHOD,
         Opcode.NEW, Opcode.CONSTRUCTOR, Opcode.GETFIELD, Opcode.PUTFIELD,
         Opcode.GETSTATIC, Opcode.PUTSTATIC, Opcode.CHECKCAST, Opcode.BOX,
         Opcode.UNBOX, Opcode.DECLARE_NS ->
      formatConstPool(sb, constants, v.poolIndex());

    case Opcode.JUMP, Opcode.JUMP_IF_TRUE, Opcode.JUMP_IF_FALSE,
         Opcode.JUMP_IF_NULL, Opcode.JUMP_IF_NONNULL ->
      sb.append(" B").append(v.jumpTarget());

    case Opcode.NEW_FIXED_ARRAY, Opcode.NEW_MULTI_ARRAY,
         Opcode.LOAD_ARRAY, Opcode.STORE_ARRAY -> {
      sb.append(" ").append(v.payload()).append(",");
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
    if (c instanceof IRClass cls)
      return "IRClass(" + cls.name + ")";
    if (c instanceof Class<?> cls)
      return "<" + cls.getName() + ">";
    if (c instanceof Method m)
      return "<" + m.getDeclaringClass().getSimpleName() + "." + m.getName() +
             ">";
    if (c instanceof Field f)
      return "<" + f.getDeclaringClass().getSimpleName() + "." + f.getName() +
             ">";
    if (c instanceof Constructor<?> cons)
      return "<" + cons.getDeclaringClass().getSimpleName() + ">";
    if (c instanceof Descriptors.Indy i)
      return i.name() + "(" + i.bootstrap().getName() + ")";
    return c.getClass().getSimpleName();
  }
}
