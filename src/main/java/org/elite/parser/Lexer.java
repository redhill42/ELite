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

public abstract class Lexer {
  /**
   * Make a deep clone to ensure not shared with other lexers.
   */
  public abstract void dirtyCopy();

  /**
   * Import operators from another lexer.
   */
  public abstract void importFrom(Lexer other);

  /**
   * Add an operator.
   */
  public void addOperator(String tok, int token, int token2) {
    addOperator(tok, tok, token, token2);
  }

  /**
   * Add an operator.
   */
  public abstract void addOperator(String tok, String name, int token,
                                   int token2);

  /**
   * Remove an operator.
   */
  public abstract void removeOperator(String tok);

  /**
   * Get the operator.
   */
  public abstract Operator getOperator(String tok);

  /**
   * Scan the next token.
   */
  public abstract void scan(Scanner s);
}
