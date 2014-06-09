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

package org.operamasks.util;

import java.util.Arrays;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import javax.el.ELContext;
import org.operamasks.el.eval.TypeCoercion;

public class Utils
{
    private Utils() {}

    /**
     * Get the wrapper class if the given class is a primitive type. Returns
     * the same class object if the given class is not a primitive type.
     */
    public static Class getWrapperClass(Class c) {
	if (c.isPrimitive()) {
	    if (c == Boolean.TYPE) {
		return Boolean.class;
	    } else if (c == Byte.TYPE) {
		return Byte.class;
	    } else if (c == Character.TYPE) {
		return Character.class;
	    } else if (c == Short.TYPE) {
		return Short.class;
	    } else if (c == Integer.TYPE) {
		return Integer.class;
	    } else if (c == Long.TYPE) {
		return Long.class;
	    } else if (c == Float.TYPE) {
		return Float.class;
	    } else if (c == Double.TYPE) {
		return Double.class;
	    } else {
		return null;
	    }
	} else {
	    return c;
	}
    }

    public static ClassLoader getClassLoader(ELContext elctx) {
        ClassLoader cl = (ClassLoader)elctx.getContext(ClassLoader.class);
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = Utils.class.getClassLoader();
            }
        }
        return cl;
    }

    public static Class findClass(String name)
        throws ClassNotFoundException
    {
        return findClass(name, null);
    }

    public static Class findClass(String name, ClassLoader loader)
        throws ClassNotFoundException
    {
        if (name == null || name.length() == 0)
            return null;

        if (name.startsWith("$"))
            name = name.substring(1);
        
        int enhanceFlagIndex = name.indexOf("$$");
        if (enhanceFlagIndex != -1)
            name = name.substring(0, name.indexOf("$$"));

        Class type;

        int dim = 0;
        while (name.endsWith("[]")) {
            dim++;
            name = name.substring(0, name.length()-2);
        }

        type = findPrimitiveClass(name);
        if (type == null) {
            try {
                if (loader != null) {
                    type = Class.forName(name, true, loader);
                } else {
                    type = Class.forName(name);
                }
            } catch (ClassNotFoundException e) {
                type = Class.forName(name, true, Thread.currentThread().getContextClassLoader());
            }
        }

        if (dim == 0) {
            return type;
        } else {
            return java.lang.reflect.Array.newInstance(type, new int[dim]).getClass();
        }
    }

    private static final String PRIMITIVE_NAMES[] = {
        "boolean", "byte", "char", "double", "float", "int", "long", "short", "void"
    };
    private static final Class PRIMITIVE_TYPES[] = {
        Boolean.TYPE, Byte.TYPE, Character.TYPE, Double.TYPE, Float.TYPE,
        Integer.TYPE, Long.TYPE, Short.TYPE, Void.TYPE
    };

    private static Class findPrimitiveClass(String name) {
        int i = Arrays.binarySearch(PRIMITIVE_NAMES, name);
        if (i >= 0)
            return PRIMITIVE_TYPES[i];
        return null;
    }

    /**
     * Check field to detect class modification.
     */
    public static Field checkField(Class<?> targetClass, Field field) {
        Class<?> declClass = field.getDeclaringClass();
        if (!declClass.isAssignableFrom(targetClass)) {
            try {
                ClassLoader loader = targetClass.getClassLoader();
                declClass = findClass(declClass.getName(), loader);
                field = declClass.getDeclaredField(field.getName());
            } catch (NoSuchFieldException ex) {
                field = null;
            } catch (ClassNotFoundException ex) {
                field = null;
            }
        }
        return field;
    }

    /**
     * Check method to detect class modification.
     */
    public static Method checkMethod(Class<?> targetClass, Method method) {
        Class<?> declClass = method.getDeclaringClass();

        if (!declClass.isAssignableFrom(targetClass)) {
            try {
                ClassLoader loader = targetClass.getClassLoader();
                String name = method.getName();
                Class[] params = method.getParameterTypes();

                declClass = findClass(declClass.getName(), loader);
                for (int i = 0; i < params.length; i++) {
                    params[i] = findClass(params[i].getName(), loader);
                }

                method = declClass.getDeclaredMethod(name, params);
                method.setAccessible(true);
            } catch (NoSuchMethodException ex) {
                method = null;
            } catch (ClassNotFoundException ex) {
                method = null;
            }
        }

        return method;
    }

    public static Object[] buildParameterList(Class[] types, Object[] params, boolean isVarArgs) {
        int      nargs  = types.length;
        Object[] values = new Object[nargs];

        if (isVarArgs) {
            --nargs;

            int vargc = params.length - nargs;
            if (vargc < 0) vargc = 0;

            assert types[nargs].isArray();
            Class argtype = types[nargs].getComponentType();
            Object vargs = Array.newInstance(argtype, vargc);
            for (int i = 0; i < vargc; i++) {
                Array.set(vargs, i, TypeCoercion.coerce(params[i+nargs], argtype));
            }
            values[nargs] = vargs;
        }

        for (int i = 0; i < nargs; i++) {
            if (i < params.length) {
                values[i] = TypeCoercion.coerce(params[i], types[i]);
            } else {
                values[i] = TypeCoercion.coerce(null, types[i]);
            }
        }

        return values;
    }

    // From RFC 1738
    private static final String VALID_SCHEME_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+.-";

    public static boolean isAbsoluteURL(String url) {
        if (url == null)
            return false;

        // do a simple check first
        int colon = url.indexOf(':');
        if (colon == -1)
            return false;

        // make sure that scheme is valid
        for (int i = 0; i < colon; i++) {
            if (VALID_SCHEME_CHARS.indexOf(url.charAt(i)) == -1)
                return false;
        }

        return true;
    }
}
