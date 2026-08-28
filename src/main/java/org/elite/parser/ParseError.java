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

/**
 * A parse error recorded during error recovery.
 *
 * @param pos        the error position, as encoded by {@link Position}
 * @param message    the error message
 * @param sourceLine the source line at the error position, or
 *                   {@code null} if unknown
 */
public record ParseError(int pos, String message, String sourceLine) {

  /**
   * The 1-based line number of the error position.
   */
  public int line() {
    return Position.line(pos);
  }

  /**
   * The 1-based column number of the error position.
   */
  public int column() {
    return Position.column(pos);
  }

  /**
   * The source line with a caret marker on the next line for error
   * display, or an empty string if the source line is unknown.
   * The characters of the source line up to the error column are
   * reproduced on the caret line, so that alignment is preserved
   * even with tabs.
   */
  public String snippet() {
    if (sourceLine == null)
      return "";

    StringBuilder sb = new StringBuilder();
    sb.append(sourceLine).append('\n');
    int col = column();
    for (int i = 1; i < col; i++) {
      char c = i <= sourceLine.length() ? sourceLine.charAt(i - 1) : ' ';
      sb.append(c == '\t' ? '\t' : ' ');
    }
    sb.append('^');
    return sb.toString();
  }
}
