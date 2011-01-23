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

import java.util.regex.Pattern;
import java.util.Locale;
import java.lang.reflect.Method;
import javax.faces.context.FacesContext;
import elite.lang.Closure;

public class ELUtils
{
    private ELUtils() {}

    public static final Class[]   NO_ARGS   = new Class[0];
    public static final Object[]  NO_VALUES = new Object[0];
    public static final Closure[] NO_PARAMS = new Closure[0];
    public static final Object    NO_RESULT = new Object();
    
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
        StringBuffer buf = new StringBuffer();
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
        if (v instanceof CharSequence) {
            CharSequence s = (CharSequence)v;
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

    public static String getMethodDescriptor(Method method) {
        StringBuilder buf = new StringBuilder();
        buf.append('(');
        for (Class type : method.getParameterTypes()) {
            getClassDescriptor(buf, type);
        }
        buf.append(')');
        getClassDescriptor(buf, method.getReturnType());
        return buf.toString();
    }

    public static String getClassDescriptor(Class c) {
        StringBuilder buf = new StringBuilder();
        getClassDescriptor(buf, c);
        return buf.toString();
    }

    public static void getClassDescriptor(StringBuilder buf, Class c) {
        Class d = c;
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
