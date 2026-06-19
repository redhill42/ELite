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

package org.operamasks.util;

import java.lang.reflect.AccessibleObject;
import java.util.Arrays;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import javax.el.ELContext;
import org.operamasks.el.eval.TypeCoercion;
import sun.misc.Unsafe;

public class Utils
{
    private Utils() {}
    private static final int overrideOffset;
    public static final Unsafe UNSAFE;

    private static class Throws {
        @SuppressWarnings("unchecked")
        public static <T extends Throwable> RuntimeException sneakyThrows(Throwable throwable) throws T {
            throw (T) throwable;
        }
    }

    static {
        /*
         * 通过反射获取 Unsafe 实例，这是JDK故意保留的使用方式
         * 通过 Unsafe setAccessible 的 Field 和 未 setAccessible 的 Field 逐一对比获取 override 字段的内存偏移（字段偏移在所有子类型中固定）
         * 通过 override 偏移，即可绕过权限校验强行设置所有 setAccessible
         */
        try {
            Field accessible = Unsafe.class.getDeclaredField("theUnsafe");
            Field notAccessible = Unsafe.class.getDeclaredField("theUnsafe");
            accessible.setAccessible(true);
            notAccessible.setAccessible(false);
            Unsafe unsafe = (Unsafe) accessible.get(null);
            // override 布尔型字节偏移量。在java17应该是 12
            int i = 0;
            while (unsafe.getBoolean(accessible, i) == unsafe.getBoolean(notAccessible, i)) {i++;}
            overrideOffset = i;
            UNSAFE = unsafe;
        } catch (Throwable e) {
            throw Throws.sneakyThrows(e);
        }
    }

    @SuppressWarnings({"deprecation", "UnusedReturnValue"})
    public static <T extends AccessibleObject> T setAccessible(T object) {
        if (object == null) {
            return null;
        }
        if (object.isAccessible()) {
            return object;
        }
        UNSAFE.putBoolean(object, overrideOffset, true);
        return object;
    }

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
                setAccessible(method);
            } catch (NoSuchMethodException | ClassNotFoundException ex) {
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
