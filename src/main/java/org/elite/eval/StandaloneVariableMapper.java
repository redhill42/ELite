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

package org.elite.eval;

import org.elite.eval.closure.LiteralClosure;
import javax.el.ELContext;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;

@SuppressWarnings("unused")
public class StandaloneVariableMapper extends VariableMapperImpl {

  public StandaloneVariableMapper(String[] args) {
    super.setVariable("ARGV",   new LiteralClosure(args));
    super.setVariable("ENV",    new LiteralClosure(System.getenv()));
    super.setVariable("stdin",  new LiteralReader(System.in));
    super.setVariable("stdout", new LiteralWriter(System.out));
    super.setVariable("stderr", new LiteralWriter(System.err));
    super.setVariable("endl",   new LiteralClosure(System.lineSeparator()));
  }

  private static class LiteralReader extends LiteralClosure {
    LiteralReader(InputStream in) {
      super(new InputStreamReader(in));
    }

    public void setValue(ELContext elctx, Object value) {
      Reader in;
      if (value instanceof Reader)
        in = (Reader)value;
      else if (value instanceof InputStream)
        in = new InputStreamReader((InputStream)value);
      else
        throw new EvaluationException(elctx, "Reader expected");
      super.setValue(elctx, in);
    }
  }

  private static class LiteralWriter extends LiteralClosure {
    LiteralWriter(OutputStream out) {
      super(new PrintWriter(out), true);
    }

    public void setValue(ELContext elctx, Object value) {
      PrintWriter out;
      if (value instanceof PrintWriter)
        out = (PrintWriter)value;
      else if (value instanceof Writer)
        out = new PrintWriter((Writer)value, true);
      else if (value instanceof OutputStream)
        out = new PrintWriter((OutputStream)value, true);
      else
        throw new EvaluationException(elctx, "Writer expected");
      super.setValue(elctx, out);
    }
  }
}
