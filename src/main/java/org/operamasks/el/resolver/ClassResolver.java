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
        if (cr == null)
            context.putContext(ClassResolver.class, cr = new ClassResolver());
        return cr;
    }
    
    private ClassLoader          loader;
    private List<String>         packages = new ArrayList<String>();
    private Map<String,String>   aliases  = new HashMap<String,String>();
    private Map<String,Class<?>> cache    = new HashMap<String,Class<?>>();

    public ClassResolver() {
        this(Thread.currentThread().getContextClassLoader());
    }

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
