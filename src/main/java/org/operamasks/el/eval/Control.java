/*
 * Copyright (c) 2006-2011 Daniel Yuan.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */

package org.operamasks.el.eval;

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
