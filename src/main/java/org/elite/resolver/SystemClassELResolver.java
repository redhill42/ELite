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

package org.elite.resolver;

import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.beans.FeatureDescriptor;
import java.io.Serializable;

import javax.el.ELResolver;
import javax.el.ELContext;
import javax.el.PropertyNotWritableException;

public class SystemClassELResolver extends ELResolver
{
    private final Map<String,JavaPackage> packages;
    private static final List<String> systemPackages;

    // Collect packages from system class loader
    static {
        List<String> names = new ArrayList<>();
        for (Package pkg : Package.getPackages()) {
            names.add(pkg.getName());
        }
        systemPackages = names;
    }

    public SystemClassELResolver() {
        List<String> names = new ArrayList<>(systemPackages);

        // add packages from thread context class loader
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        names.addAll(new TCLPackages(cl).findPackages());

        Map<String,JavaPackage> map = new HashMap<>();
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
            clsname = ((JavaPackage)base).getName()+ "." + property;
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
            Class<?> c = ClassResolver.getInstance(context).resolveClass(clsname);
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
            clsname = ((JavaPackage)base).getName() + "." + property;
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
            super(cl);
        }

        public List<String> findPackages() {
            Set<String> pkgs = new HashSet<>();
            for (Package pkg : super.getPackages()) {
                String name = pkg.getName();
                while (name != null) {
                    pkgs.add(name);
                    int p = name.lastIndexOf('.');
                    name = (p==-1) ? null : name.substring(0,p);
                }
            }
            return new ArrayList<>(pkgs);
        }
    }
}
