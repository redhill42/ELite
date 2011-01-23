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

import javax.el.FunctionMapper;
import javax.el.ELException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.operamasks.util.Utils;

import static org.operamasks.el.resources.Resources.*;

public class FunctionMapperImpl extends FunctionMapper
    implements java.io.Serializable
{
    private static final long serialVersionUID = 4287860736854130693L;

    private Map<String,Method> map = new HashMap<String, Method>();

    public FunctionMapperImpl() {}

    public Method resolveFunction(String prefix, String localName) {
        return map.get(key(prefix, localName));
    }

    public void addFunction(String prefix, String localName, Method method) {
        String key = key(prefix, localName);
        if (!map.containsKey(key)) {
            map.put(key, method);
        }
    }

    public void addFunction(String prefix, String localName,
                            Class clazz, String methodName, Class[] args)
    {
        try {
            Method method = clazz.getMethod(methodName, args);
            addFunction(prefix, localName, method);
        }catch (NoSuchMethodException ex) {
            throw new ELException(_T(EL_FN_NO_SUCH_METHOD,
                                     methodName,
                                     key(prefix, localName),
                                     clazz.getName()));
        }
    }

    private String key(String prefix, String localName) {
        if (prefix == null || prefix.length() == 0)
            return localName;
        return prefix + ":" + localName;
    }

    public Map<String,Method> getFunctionMap() {
        return map;
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        out.writeInt(map.size());
        for (Map.Entry<String,Method> e : map.entrySet()) {
            String varName = e.getKey();
            Method method = e.getValue();
            Class[] paramTypes = method.getParameterTypes();

            out.writeUTF(varName);
            out.writeUTF(method.getDeclaringClass().getName());
            out.writeUTF(method.getName());
            out.writeInt(paramTypes.length);
            for (Class type : paramTypes) {
                out.writeUTF(type.getName());
            }
        }
    }

    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        map = new HashMap<String, Method>();
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            String varName;
            Method method;

            varName = in.readUTF();
            Class clazz = Utils.findClass(in.readUTF());
            String methodName = in.readUTF();
            Class[] paramTypes = new Class[in.readInt()];
            for (int j = 0; j < paramTypes.length; j++) {
                paramTypes[j] = Utils.findClass(in.readUTF());
            }
            try {
                method = clazz.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ex) {
                throw new RuntimeException(ex);
            }
            map.put(varName, method);
        }
    }
}
