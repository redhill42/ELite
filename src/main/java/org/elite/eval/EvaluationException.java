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

import javax.el.ELException;
import javax.el.ELContext;

public class EvaluationException extends ELException
{
    private final Frame frame;

    public EvaluationException(ELContext elctx, String message) {
        super(message);
        frame = (elctx == null) ? null : StackTrace.getFrame(elctx);
    }

    public EvaluationException(ELContext elctx, Throwable cause) {
        super(cause);
        frame = (elctx == null) ? null : StackTrace.getFrame(elctx);
    }

    public EvaluationException(ELContext elctx, String message, Throwable cause) {
        super(message, cause);
        frame = (elctx == null) ? null : StackTrace.getFrame(elctx);
    }

    public String getRawMessage() {
        return super.getMessage();
    }
    
    public String getMessage() {
        if (frame == null) {
            return super.getMessage();
        } else {
            StringBuilder buf = new StringBuilder();
            buf.append(super.getMessage());
            buf.append("\n");
            for (Frame f = frame; f != null; f = f.getNext()) {
                buf.append("\tat ").append(f).append("\n");
            }
            buf.append("-------------------------");
            return buf.toString();
        }
    }

    public String getFileName() {
        return (frame == null) ? null : frame.getFileName();
    }

    public int getLineNumber() {
        return (frame == null) ? -1 : frame.getLineNumber();
    }

    public int getColumnNumber() {
        return (frame == null) ? -1 : frame.getColumnNumber();
    }
}
