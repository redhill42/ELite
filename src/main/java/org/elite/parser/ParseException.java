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

import javax.el.ELException;
import java.util.Collections;
import java.util.List;

public class ParseException extends ELException {
  private final String file;
  private final List<ParseError> errors;

  public ParseException(String file, int line, int column, String message) {
    this(file, Collections.singletonList(
      new ParseError(Position.make(line, column), message, null)));
  }

  public ParseException(String file, List<ParseError> errors) {
    this.file = file;
    this.errors = errors;
  }

  public String getFileName() {
    return file;
  }

  public List<ParseError> getErrors() {
    return errors;
  }

  @Override
  public String getMessage() {
    StringBuilder sb = new StringBuilder();
    for (ParseError err : errors) {
      if (file != null)
        sb.append(file).append(':').append(err.line()).append(':')
          .append(err.column()).append(": ");
      else
        sb.append("line ").append(err.line()).append(": ");
      sb.append(err.message());
      if (!err.snippet().isEmpty()) {
        sb.append('\n').append(err.snippet());
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
