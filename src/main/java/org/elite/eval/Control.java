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

/**
 * The control statements (break, continue, return) are implemented as a Java exception.
 */
public class Control extends RuntimeException
{
    public Control(String message) {
        super(message);
    }

    public Throwable fillInStackTrace() {
        return this;  // performance
    }

    public static class Break extends Control {
        public Break() {
            super("break outside loop.");
        }
    }

    public static class Continue extends Control {
        public Continue() {
            super("continue outside loop.");
        }
    }

    public static class Return extends Control {
        private Object result;

        public Return(Object result) {
            super("return outside procedure.");
            this.result = result;
        }

        public Object getResult() {
            return result;
        }
    }

    public static class Escape extends Control {
        private Object result;
        private Object cpoint;

        public Escape(Object result) {
            super("called outside catch block.");
            this.result = result;
        }

        public Escape(Object result, Object cpoint) {
            super("called outside catch block.");
            this.result = result;
            this.cpoint = cpoint;
        }

        public Object getResult() {
            return this.result;
        }

        public Object getCatchPoint() {
            return this.cpoint;
        }
    }
}
