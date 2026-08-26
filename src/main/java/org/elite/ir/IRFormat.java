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

/**
 * Bit-level encoding constants for IR instructions.
 * <p>
 * Header word (32 bits):
 * <pre>
 *     ┌──31───────────────────┬─15───8──┬─7────0─┐
 *     │       operand         │ payload │ opcode │
 *     │        16 bit         │ 9 bits  │ 7 bits │
 *     └───────────────────────┴─────────┴────────┘
 * </pre>
 * <p>
 */
final class IRFormat {
  private IRFormat() {
  }

  // Bit shifts
  public static final int PAYLOAD_SHIFT = 7;
  public static final int OPERAND_SHIFT = 16;

  // Primitive type IDs
  public static final int K_NONE    = 0;
  public static final int K_INT     = 1;
  public static final int K_LONG    = 2;
  public static final int K_FLOAT   = 3;
  public static final int K_DOUBLE  = 4;
  public static final int K_BOOL    = 5;
  public static final int K_DYNAMIC = 6;

  public static int typeKind(Class<?> type) {
    if (type == int.class)
      return K_INT;
    if (type == long.class)
      return K_LONG;
    if (type == float.class)
      return K_FLOAT;
    if (type == double.class)
      return K_DOUBLE;
    if (type == boolean.class)
      return K_BOOL;
    throw new AssertionError();
  }

  // ── Packing helpers ──

  /**
   * Pack a 1-word instruction.
   */
  public static int pack(int opcode, int payload, int operand) {
    return opcode |
           ((payload & 0x1FF) << PAYLOAD_SHIFT) |
           (operand << OPERAND_SHIFT);
  }

  // ── Decoding helpers ──

  public static int opcode(int header) {
    return header & 0x7F;
  }

  public static int payload(int header) {
    int payload = (header >>> PAYLOAD_SHIFT) & 0x1FF;
    if ((payload & 0x100) != 0)
      payload |= 0xFFFFF700;
    return payload;
  }

  public static int operand(int header) {
    return header >> OPERAND_SHIFT;
  }

  public static boolean match(int inst, int opcode, int operand) {
    return opcode(inst) == opcode && operand(inst) == operand;
  }
}
