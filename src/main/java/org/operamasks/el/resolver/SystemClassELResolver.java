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

import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.beans.FeatureDescriptor;
import java.io.Serializable;
import java.lang.reflect.Method;

import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

public class SystemClassELResolver extends ELResolver
{
    private Map<String,JavaPackage> packages;
    private static List<String> systemPackages;
    private static PackageCollector collector;

    // Collect packages from system class loader
    static {
        List<String> names = new ArrayList<String>();
        for (Package pkg : Package.getPackages()) {
            names.add(pkg.getName());
        }
        systemPackages = names;
    }

    /**
     * Set a hook to collect packages from another source, such as
     * OSGi bundle exported packages or servlet class loader.
     */
    public static void setPackageCollector(PackageCollector collector) {
        SystemClassELResolver.collector = collector;
    }

    public SystemClassELResolver() {
        List<String> names = new ArrayList<String>(systemPackages);
        if (collector != null) {
            names.addAll(collector.findPackages());
        }

        // add packages from thread context class loader
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        names.addAll(new TCLPackages(cl).findPackages());

        Map<String,JavaPackage> map = new HashMap<String,JavaPackage>();
        for (String name : names) {
            while (name != null) {
                if (!map.containsKey(name)) {
                    map.put(name, new JavaPackage(name));
                }
                int p = name.lastIndexOf('.');
                name = (p==-1) ? null : name.substring(0,p);
            }
        }

        this.packages = map;
    }

    public Object getValue(ELContext context, Object base, Object property) {
        String clsname;

        if (property == null) {
            return null;
        } else if (base == null) {
            clsname = property.toString();
        } else if (base instanceof JavaPackage) {
            clsname = ((JavaPackage)base).getName()+ "." + property.toString();
        } else if (base instanceof Class && "class".equals(property)) {
            context.setPropertyResolved(true);
            return base;
        } else {
            return null;
        }

        JavaPackage pkg = packages.get(clsname);
        if (pkg != null) {
            context.setPropertyResolved(true);
            return pkg;
        }

        try {
            Class c = ClassResolver.getInstance(context).resolveClass(clsname);
            context.setPropertyResolved(true);
            return c;
        } catch (ClassNotFoundException ex) {
            // fallthrough
        }

        return null;
    }

    public Class<?> getType(ELContext context, Object base, Object property) {
        String clsname;

        if (property == null) {
            return null;
        } else if (base == null) {
            clsname = property.toString();
        } else if (base instanceof JavaPackage) {
            clsname = ((JavaPackage)base).getName() + "." + property.toString();
        } else if (base instanceof Class && "class".equals(property)) {
            context.setPropertyResolved(true);
            return Class.class;
        } else {
            return null;
        }

        if (packages.containsKey(clsname)) {
            context.setPropertyResolved(true);
            return JavaPackage.class;
        }

        try {
            ClassResolver.getInstance(context).resolveClass(clsname);
            context.setPropertyResolved(true);
            return Class.class;
        } catch (ClassNotFoundException ex) {
            // fallthrough
        }

        return null;
    }

    public void setValue(ELContext context, Object base, Object property, Object value) {
        if (base instanceof JavaPackage && property != null) {
            throw new PropertyNotWritableException();
        }
    }

    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (base instanceof JavaPackage && property != null) {
            context.setPropertyResolved(true);
            return true;
        }
        return false;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return null;
    }

    // Java packages map ---------------------

    private static class JavaPackage implements Serializable {
        private final String name;
        private static final long serialVersionUID = 8152143883688685673L;

        public JavaPackage(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public String toString() {
            return "package " + name;
        }

        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj instanceof JavaPackage) {
                return name.equals(((JavaPackage)obj).getName());
            } else {
                return false;
            }
        }

        public int hashCode() {
            return name.hashCode();
        }
    }
    
    private static class TCLPackages extends ClassLoader implements PackageCollector{
        TCLPackages(ClassLoader cl) {
            super(getActualClassLoader(cl));
        }

        public List<String> findPackages() {
            Set<String> pkgs = new HashSet<String>();
            for (Package pkg : super.getPackages()) {
                String name = pkg.getName();
                while (name != null) {
                    pkgs.add(name);
                    int p = name.lastIndexOf('.');
                    name = (p==-1) ? null : name.substring(0,p);
                }
            }
            return new ArrayList<String>(pkgs);
        }
    }

    private static final String CLOUDWAY_LOADER = "com.cloudway.internal.web.container.ServletClassLoader";
    private static final String CLOUDWAY_JSP_LOADER = "getJspClassLoader";

    private static ClassLoader getActualClassLoader(ClassLoader cl) {
        try {
            if (cl.getClass().getName().equals(CLOUDWAY_LOADER)) {
                Method method = cl.getClass().getMethod(CLOUDWAY_JSP_LOADER);
                return (ClassLoader)method.invoke(cl);
            } else {
                return cl;
            }
        } catch (Throwable ex) {
            return cl;
        }
    }
}
