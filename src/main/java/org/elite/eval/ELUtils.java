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

import java.util.regex.Pattern;
import java.util.Locale;
import java.lang.reflect.Method;
import javax.faces.context.FacesContext;
import elite.lang.Closure;

public class ELUtils
{
    private ELUtils() {}

    public static final Class<?>[] NO_ARGS   = new Class[0];
    public static final Object[]   NO_VALUES = new Object[0];
    public static final Closure[]  NO_PARAMS = new Closure[0];
    public static final Object     NO_RESULT = new Object();

    static final boolean facesContextPresent = classPresent("javax.faces.context.FacesContext");

    public static Locale getCurrentLocale() {
        if (facesContextPresent) {
            FacesContext context = FacesContext.getCurrentInstance();
            if (context != null && context.getViewRoot() != null) {
                return context.getViewRoot().getLocale();
            }
        }

        return Locale.getDefault();
    }

    public static boolean classPresent(String name) {
        try {
            Class.forName(name);
        } catch (Throwable ex) {
            return false;
        }
        return true;
    }

    public static String getQuotedString(String str) {
        StringBuilder buf = new StringBuilder();
        buf.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
            case '\t':
                buf.append("\\t");
                break;
            case '\n':
                buf.append("\\n");
                break;
            case '\r':
                buf.append("\\r");
                break;
            case '"':
                buf.append("\\\"");
                break;
            case '\\':
                buf.append("\\\\");
                break;
            default:
                buf.append(c);
            }
        }
        buf.append('"');
        return buf.toString();
    }

    private static final Pattern NUMBER_PATTERN =
        Pattern.compile("^[-+]?(\\d+|\\d+\\.\\d*|\\d*\\.\\d+)([eE][-+]?\\d+)?$");

    public static boolean looksLikeNumber(Object v) {
        if (v instanceof Number) {
            return true;
        } else if (v instanceof CharSequence) {
            return NUMBER_PATTERN.matcher((CharSequence)v).matches();
        } else {
            return false;
        }
    }

    public static boolean looksLikeFloat(Object v) {
        if (v instanceof CharSequence s) {
            int len = s.length();
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E')
                    return true;
            }
        }
        return false;
    }

    public static String capitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        } else {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    public static String decapitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        } else {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
    }

    public static String escape(String s) {
        StringBuilder buf = new StringBuilder();
        escape(buf, s);
        return buf.toString();
    }

    public static StringBuilder escape(StringBuilder buf, String s) {
        boolean escaped = false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            String esc = escape(c);
            if (esc != null) {
                if (!escaped) {
                    buf.append('"');
                    buf.append(s, 0, i);
                    escaped = true;
                }
                buf.append(esc);
            } else if (escaped) {
                buf.append(c);
            }
        }

        if (escaped) {
            buf.append('"');
        } else {
            buf.append('\'');
            buf.append(s);
            buf.append('\'');
        }
        return buf;
    }

    public static String escape(char c) {
        switch (c) {
        case '\r': return "\\r";
        case '\n': return "\\n";
        case '\f': return "\\f";
        case '\b': return "\\b";
        case '\t': return "\\t";
        case '\\': return "\\\\";
        case '\'': return "'";
        case '"':  return "\\\"";

        case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
            return "\\00" + Integer.toOctalString(c);
        case 11: case 14: case 15:
        case 16: case 17: case 18: case 19: case 20: case 21: case 22: case 23:
        case 24: case 25: case 26: case 27: case 28: case 29: case 30: case 31:
            return "\\0" + Integer.toOctalString(c);

        default:
            return null;
        }
    }

    public static boolean isJavaIdentifier(String name) {
        if (name == null || name.isEmpty())
            return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0)))
            return false;
        for (int i = 1; i < name.length(); i++)
            if (!Character.isJavaIdentifierPart(name.charAt(i)))
                return false;
        return true;
    }

    public static String mangle(String name) {
        return name.replace("<", "%lt%")
                   .replace(">", "%gt%")
                   .replace("[", "%lb%")
                   .replace("]", "%rb%")
                   .replace("/", "%div%");
    }

    public static String demangle(String name) {
        return name.replace("%lt%", "<")
                   .replace("%gt%", ">")
                   .replace("%lb%", "[")
                   .replace("%rb%", "]")
                   .replace("%div%", "/");
    }

    public static String getMethodDescriptor(Method method) {
        StringBuilder buf = new StringBuilder();
        buf.append('(');
        for (Class<?> type : method.getParameterTypes()) {
            getClassDescriptor(buf, type);
        }
        buf.append(')');
        getClassDescriptor(buf, method.getReturnType());
        return buf.toString();
    }

    public static String getClassDescriptor(Class<?> c) {
        StringBuilder buf = new StringBuilder();
        getClassDescriptor(buf, c);
        return buf.toString();
    }

    public static void getClassDescriptor(StringBuilder buf, Class<?> c) {
        Class<?> d = c;
        while (true) {
            if (d.isPrimitive()) {
                char car;
                if (d == Void.TYPE) {
                    car = 'V';
                } else if (d == Boolean.TYPE) {
                    car = 'Z';
                } else if (d == Character.TYPE) {
                    car = 'C';
                } else if (d == Byte.TYPE) {
                    car = 'B';
                } else if (d == Short.TYPE) {
                    car = 'S';
                } else if (d == Integer.TYPE) {
                    car = 'I';
                } else if (d == Long.TYPE) {
                    car = 'J';
                } else if (d == Float.TYPE) {
                    car = 'F';
                } else if (d == Double.TYPE) {
                    car = 'D';
                } else {
                    throw new AssertionError();
                }
                buf.append(car);
                return;
            } else if (d.isArray()) {
                buf.append('[');
                d = d.getComponentType();
            } else {
                buf.append('L');
                buf.append(d.getName().replace('.', '/'));
                buf.append(';');
                return;
            }
        }
    }
}
