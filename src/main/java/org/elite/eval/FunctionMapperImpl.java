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

import javax.el.FunctionMapper;
import javax.el.ELException;
import java.io.Serial;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.IOException;
import org.elite.util.Utils;

import static org.elite.resources.Resources.*;

public class FunctionMapperImpl extends FunctionMapper
    implements java.io.Serializable
{
    @Serial
    private static final long serialVersionUID = 4287860736854130693L;

    private Map<String,Method> map = new HashMap<>();

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
                            Class<?> clazz, String methodName, Class<?>[] args)
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

    @Serial
    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        out.writeInt(map.size());
        for (Map.Entry<String,Method> e : map.entrySet()) {
            String varName = e.getKey();
            Method method = e.getValue();
            Class<?>[] paramTypes = method.getParameterTypes();

            out.writeUTF(varName);
            out.writeUTF(method.getDeclaringClass().getName());
            out.writeUTF(method.getName());
            out.writeInt(paramTypes.length);
            for (Class<?> type : paramTypes) {
                out.writeUTF(type.getName());
            }
        }
    }

    @Serial
    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        map = new HashMap<>();
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            String varName;
            Method method;

            varName = in.readUTF();
            Class<?> clazz = Utils.findClass(in.readUTF());
            String methodName = in.readUTF();
            Class<?>[] paramTypes = new Class[in.readInt()];
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
