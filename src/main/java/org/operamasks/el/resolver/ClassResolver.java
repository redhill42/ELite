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

package org.operamasks.el.resolver;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import javax.el.ELContext;
import org.operamasks.util.Utils;

public class ClassResolver
{
    public static ClassResolver getInstance(ELContext context) {
        ClassResolver cr = (ClassResolver)context.getContext(ClassResolver.class);
        if (cr == null) {
            cr = new ClassResolver(Utils.getClassLoader(context));
            context.putContext(ClassResolver.class, cr);
        }
        return cr;
    }
    
    private ClassLoader          loader;
    private List<String>         packages = new ArrayList<String>();
    private Map<String,String>   aliases  = new HashMap<String,String>();
    private Map<String,Class<?>> cache    = new HashMap<String,Class<?>>();

    public ClassResolver(ClassLoader loader) {
        this.loader = loader;

        addImport("elite.lang.*");
        addImport("java.lang.*");
        addImport("java.util.*");
        addImport("java.lang.reflect.Array");
        addImport("java.math.BigInteger");
        addImport("java.math.BigDecimal");
    }

    public void addImport(String name) {
        if (name.endsWith(".*")) {
            String pkg = name.substring(0, name.length()-2);
            if (!packages.contains(pkg)) {
                packages.add(pkg);
            }
        } else {
            String simpleName = name.substring(name.lastIndexOf('.') + 1);
            aliases.put(simpleName, name);
        }
    }

    public Class<?> resolveClass(String name)
        throws ClassNotFoundException
    {
        Class<?> c;
        String qname;

        if ((c = cache.get(name)) != null) {
            return c;
        }

        if (name.indexOf('.') == -1) {
            qname = aliases.get(name);
            if (qname != null) {
                if ((c = resolveClass0(qname)) != null) {
                    cache.put(name, c);
                    return c;
                } else {
                    throw new ClassNotFoundException(qname);
                }
            }

            for (String pkg : packages) {
                qname = pkg + "." + name;
                if ((c = resolveClass0(qname)) != null) {
                    cache.put(name, c);
                    return c;
                }
            }
        }

        if ((c = resolveClass0(name)) != null) {
            cache.put(name, c);
            return c;
        } else {
            throw new ClassNotFoundException(name);
        }
    }

    protected Class<?> resolveClass0(String name) {
        try {
            return Utils.findClass(name, loader);
        } catch (ClassNotFoundException ex) {
            return null;
        } catch (NoClassDefFoundError ex) {
            return null;
        }
    }
}
