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

import elite.lang.Closure;
import org.elite.eval.closure.FieldClosure;
import org.elite.eval.closure.LiteralClosure;
import org.elite.resolver.ClassResolver;
import org.elite.resolver.MethodResolver;
import org.elite.util.Utils;
import javax.el.ELContext;
import javax.el.ELException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manages external imports during parsing, runtime, and bytecode generator.
 *
 * <p>The Parser add parsed external imports to this object. The imports then be
 * used to import externals at runtime or generate bytecode initializer code
 * when generating main method of EL program.
 */
public class ExternalImports implements Serializable {

  private final List<Module> mods = new ArrayList<>();
  private final List<String> libs = new ArrayList<>();
  private final List<String> imps = new ArrayList<>();

  // API used by Parser to add external imports.

  public ExternalImports addModule(String name, String prefix) {
    Module module = new Module(name, prefix);
    if (!mods.contains(module)) {
      mods.add(module);
    }
    return this;
  }

  public ExternalImports addLibrary(String name) {
    if (!libs.contains(name)) {
      libs.add(name);
    }
    return this;
  }

  public ExternalImports addImport(String imp) {
    if (!imps.contains(imp)) {
      imps.add(imp);
    }
    return this;
  }

  // API used by BytecodeCompiler to generate main method.

  public List<Module> getModules() {
    return mods;
  }

  public List<String> getLibraries() {
    return libs;
  }

  public List<String> getImports() {
    return imps;
  }

  // API used by runtime to import externals.

  public ELContext importExternals(ELContext elctx) {
    importModules(elctx);
    importFunctions(elctx);
    importImports(elctx);
    return elctx;
  }

  // Implementation.

  public static class Module implements Serializable {
    private final String name;
    private final String prefix;

    Module(String name, String prefix) {
      this.name = name;
      this.prefix = prefix;
    }

    public String getName() {
      return name;
    }

    public String getPrefix() {
      return prefix;
    }

    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj instanceof Module other) {
        return name.equals(other.name) &&
               (Objects.equals(prefix, other.prefix));
      } else {
        return false;
      }
    }
  }

  private void importModules(ELContext elctx) {
    if (!mods.isEmpty()) {
      MethodResolver resolver = MethodResolver.getInstance(elctx);
      for (Module mod : mods) {
        Class<?> cls = findClass(elctx, mod.name);
        resolver.addModule(elctx, cls, mod.prefix);
        for (Field field : cls.getFields()) {
          importField(elctx, field, mod.prefix);
        }
      }
    }
  }

  private void importFunctions(ELContext elctx) {
    if (!libs.isEmpty()) {
      MethodResolver resolver = MethodResolver.getInstance(elctx);

      for (String name : libs) {
        int sep = name.lastIndexOf('.');
        if (sep == -1) {
          throw new ELException("Invalid import directive: " + name);
        }

        String clsname = name.substring(0, sep);
        name = name.substring(sep+1);
        Class<?> cls = findClass(elctx, clsname);

        if (name.equals("*")) {
          resolver.addGlobalMethods(cls);
          for (Field field : cls.getFields()) {
            importField(elctx, field, null);
          }
        } else {
          for (Method method : cls.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) &&
                name.equals(method.getName())) {
              resolver.addGlobalMethod(method);
            }
          }
          try {
            importField(elctx, cls.getField(name), null);
          } catch (NoSuchFieldException ex) {
            // ignore
          }
        }
      }
    }
  }

  private static void importField(ELContext elctx, Field field, String prefix) {
    if (Modifier.isStatic(field.getModifiers())) {
      try {
        Utils.setAccessible(field);
        String name = field.getName();
        if (prefix != null)
          name = prefix + ":" + name;
        Closure closure;
        if (Modifier.isFinal(field.getModifiers())) {
          closure = new LiteralClosure(field.get(null), true);
        } else {
          closure = new FieldClosure(field);
        }
        elctx.getVariableMapper().setVariable(name, closure);
      } catch (IllegalAccessException ex) {
        // ignored
      }
    }
  }

  private void importImports(ELContext elctx) {
    if (!imps.isEmpty()) {
      ClassResolver resolver = ClassResolver.getInstance(elctx);
      for (String imp : imps) {
        resolver.addImport(imp);
      }
    }
  }


  private static Class<?> findClass(ELContext elctx, String name) {
    try {
      ClassLoader loader = Utils.getClassLoader(elctx);
      return Utils.findClass(name, loader);
    } catch (ClassNotFoundException ex) {
      throw new ELException(ex);
    }
  }
}
