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

import elite.lang.Builtin;
import org.elite.eval.ELProgram;

import javax.el.ELContext;

import static org.elite.ir.Opcode.*;

final class PeepholeOpt {
  private final ELContext elctx;
  private final IRBuilder builder;

  PeepholeOpt(ELContext elctx, IRBuilder builder) {
    this.elctx = elctx;
    this.builder = builder;
  }

  boolean run(IntList code, int opcode, int operand) {
    if (code.isEmpty() || ELProgram.OPT_LEVEL == 0)
      return false;

    int last = code.size() - 1;
    int lastInst = code.get(last);
    int lastOpcode = IRFormat.opcode(lastInst);
    int lastOperand = IRFormat.operand(lastInst);

    switch (opcode) {
    case POP: {
      switch (lastOpcode) {
      case PUSH_CONST, PUSH_TRUE, PUSH_FALSE, PUSH_NULL,
           PUSH_VAR, PUSH_GLOBAL, DUP, CLOSURE, NIL:
        // PUSH_CONST, POP -> NOP
        code.reset(last);
        return true;
      case STORE_VAR:
        // STORE_VAR, POP -> STORE_VAR_POP
        code.set(last, IRFormat.pack(STORE_VAR_POP, 0, lastOperand));
        return true;
      }
      break;
    }

    case PUSH_VAR:
      if (lastOpcode == PUSH_VAR && operand == lastOperand) {
        // PUSH_VAR x, PUSH_VAR x -> PUSH_VAR x, DUP
        code.add(IRFormat.pack(DUP, 0, 0));
        return true;
      }
      if (lastOpcode == STORE_VAR && operand == lastOperand) {
        // STORE_VAR, PUSH_VAR -> STORE_VAR, DUP
        code.add(IRFormat.pack(DUP, 0, 0));
        return true;
      }
      if (lastOpcode == STORE_VAR_POP && operand == lastOperand) {
        // STORE_VAR_POP, PUSH_VAR -> STORE_VAR
        code.set(last, IRFormat.pack(STORE_VAR, 0, operand));
        return true;
      }
      break;

    case PUSH_GLOBAL:
      if (lastOpcode == PUSH_GLOBAL && operand == lastOperand) {
        // PUSH_GLOAL x, PUSH_GLOBA x -> PUSH_GLOBAL x, DUP
        code.add(IRFormat.pack(DUP, 0, 0));
        return true;
      }
      break;

    case NOT:
      if (lastOpcode == PUSH_TRUE) {
        // PUSH_TRUE, NOT -> PUSH_FALSE
        code.set(last, IRFormat.pack(PUSH_FALSE, 0, 0));
        return true;
      }
      if (lastOpcode == PUSH_FALSE) {
        // PUSH_FALSE, NOT -> PUSH_TRUE
        code.set(last, IRFormat.pack(PUSH_TRUE, 0, 0));
        return true;
      }
      break;

    case JUMP_IF_TRUE:
      if (lastOpcode == PUSH_TRUE) {
        // PUSH_TRUE, JUMP_IF_TRUE -> JUMP
        code.set(last, IRFormat.pack(JUMP, 0, operand));
        return true;
      }
      if (lastOpcode == PUSH_FALSE) {
        // PUSH_FALSE, JUMP_IF_TRUE -> NOP
        code.reset(last);
        return true;
      }
      if (lastOpcode == NOT) {
        // NOT, JUMP_IF_TRUE -> JUMP_IF_FALSE
        code.set(last, IRFormat.pack(JUMP_IF_FALSE, 0, operand));
        return true;
      }
      break;

    case JUMP_IF_FALSE:
      if (lastOpcode == PUSH_FALSE) {
        // PUSH_FALSE, JUMP_IF_FALSE -> JUMP
        code.set(last, IRFormat.pack(JUMP, 0, operand));
        return true;
      }
      if (lastOpcode == PUSH_TRUE) {
        // PUSH_TRUE, JUMP_IF_FALSE -> NOP
        code.reset(last);
        return true;
      }
      if (lastOpcode == NOT) {
        // NOT, JUMP_IF_FALSE -> JUMP_IF_TRUE
        code.set(last, IRFormat.pack(JUMP_IF_TRUE, 0, operand));
        return true;
      }
      break;

    case JUMP_IF_NULL:
      if (lastOpcode == PUSH_NULL) {
        // PUSH_NULL, JUMP_IF_NULL -> JUMP
        code.set(last, IRFormat.pack(JUMP, 0, operand));
        return true;
      }
      break;

    case JUMP_IF_NONNULL:
      if (lastOpcode == PUSH_NULL) {
        // PUSH_NULL, JUMP_IF_NONNULL -> NOP
        code.reset(last);
        return true;
      }
      break;

    // Simple constant folder.
    case ADD, SUB, MUL, DIV, IDIV, REM, POW, CAT,
         BITAND, BITOR, XOR, SHL, SHR, USHR,
         EQ, NE, IDEQ, IDNE, LT, LE, GT, GE:
      if (code.size() >= 2) {
        int i1 = code.get(last - 1);
        int i2 = code.get(last);
        if (IRFormat.opcode(i1) != PUSH_CONST ||
            IRFormat.opcode(i2) != PUSH_CONST)
          return false;

        Object c1 = builder.getConstant(IRFormat.operand(i1));
        Object c2 = builder.getConstant(IRFormat.operand(i2));
        Object r;

        try {
          r = switch (opcode) {
            case ADD    -> Builtin.__add__(elctx, c1, c2);
            case SUB    -> Builtin.__sub__(elctx, c1, c2);
            case MUL    -> Builtin.__mul__(elctx, c1, c2);
            case DIV    -> Builtin.__div__(elctx, c1, c2);
            case IDIV   -> Builtin.__idiv__(elctx, c1, c2);
            case REM    -> Builtin.__rem__(elctx, c1, c2);
            case POW    -> Builtin.__pow__(elctx, c1, c2);
            case CAT    -> Builtin.__cat__(elctx, c1, c2);
            case BITAND -> Builtin.__bitand__(elctx, c1, c2);
            case BITOR  -> Builtin.__bitor__(elctx, c1, c2);
            case XOR    -> Builtin.__xor__(elctx, c1, c2);
            case SHL    -> Builtin.__shl__(elctx, c1, c2);
            case SHR    -> Builtin.__shr__(elctx, c1, c2);
            case USHR   -> Builtin.__ushr__(elctx, c1, c2);
            case EQ     -> Builtin.__eq__(elctx, c1, c2);
            case NE     -> Builtin.__ne__(elctx, c1, c2);
            case IDEQ   -> c1 == c2;
            case IDNE   -> c1 != c2;
            case LT     -> Builtin.__lt__(elctx, c1, c2);
            case LE     -> Builtin.__le__(elctx, c1, c2);
            case GT     -> Builtin.__gt__(elctx, c1, c2);
            case GE     -> Builtin.__ge__(elctx, c1, c2);
            default -> throw new AssertionError();
          };
        } catch (RuntimeException ex) {
          return false;
        }

        code.reset(last); last--;
        if (r == null)
          code.set(last, IRFormat.pack(PUSH_NULL, 0, 0));
        else if (r instanceof Boolean b)
          code.set(last, IRFormat.pack(b ? PUSH_TRUE : PUSH_FALSE, 0, 0));
        else
          code.set(last, IRFormat.pack(PUSH_CONST, 0, builder.putConstant(r)));
        return true;
      }
      break;

    case NEG, BITNOT:
      if (lastOpcode == PUSH_CONST) {
        Object c = builder.getConstant(lastOperand);
        Object r;

        try {
          r = switch (opcode) {
            case NEG    -> Builtin.__neg__(elctx, c);
            case BITNOT -> Builtin.__bitnot__(elctx, c);
            default -> throw new AssertionError();
          };
        } catch (RuntimeException ex) {
          return false;
        }

        if (r == null)
          code.set(last, IRFormat.pack(PUSH_NULL, 0, 0));
        else
          code.set(last, IRFormat.pack(PUSH_CONST, 0, builder.putConstant(r)));
        return true;
      }
      break;
    }

    return false;
  }
}
