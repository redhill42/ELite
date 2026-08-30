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

import elite.lang.Closure;
import elite.lang.annotation.Expando;
import elite.lang.annotation.ExpandoScope;
import org.elite.eval.EvaluationException;
import org.elite.eval.closure.MethodClosure;
import org.elite.util.SimpleCache;
import javax.el.ELContext;
import javax.el.FunctionMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class MethodResolver {
  public static MethodResolver getInstance(ELContext context) {
    MethodResolver resolver = (MethodResolver)context.getContext(
      MethodResolver.class);
    if (resolver == null) {
      resolver = new MethodResolver();
      context.putContext(MethodResolver.class, resolver);
    }
    return resolver;
  }

  private final GlobalMethodMap global = new GlobalMethodMap();
  private final Set<Class<?>> imported = new HashSet<>();

  public void addModule(ELContext elctx, Class<?> base, String prefix) {
    if (!imported.contains(base)) {
      // Invoke module initialization method if present
      try {
        Method init = base.getMethod("__init__", ELContext.class);
        if (Modifier.isStatic(init.getModifiers())) {
          init.invoke(null, elctx);
        }
      } catch (NoSuchMethodException ex) {
        // ok
      } catch (Exception ex) {
        throw new EvaluationException(elctx, ex);
      }
      imported.add(base);
    }

    global.addAllStatic(base, prefix);
  }

  public void addGlobalMethods(Class<?> base) {
    global.addAllStatic(base, null);
  }

  public void addGlobalMethod(Method method) {
    global.add(method, null);
  }

  public void attachMethod(Class<?> target, String name, Closure closure) {
    global.expandoMap.add(new ExpandoMethodClosure(name, target, closure));
  }

  public MethodClosure resolveMethod(Class<?> base, String name) {
    return getMethodClosure(base, name);
  }

  public MethodClosure resolveProtectedMethod(Class<?> base, String name) {
    return getProtectedMethodClosure(base, name);
  }

  public MethodClosure resolveStaticMethod(Class<?> base, String name) {
    return getStaticMethodClosure(base, name);
  }

  public MethodClosure resolveGlobalMethod(String name) {
    MethodClosure closure = global.get(name);
    if (closure == null)
      closure = builtin.get(name);
    return closure;
  }

  public MethodClosure resolveGlobalMethod(FunctionMapper fnm, String name) {
    MethodClosure closure = global.get(name);
    if (closure == null) {
      closure = builtin.get(name);
      if (closure == null && fnm != null)
        closure = resolveFunctionMethod(fnm, name);
    }
    return closure;
  }

  public MethodClosure resolveFunctionMethod(FunctionMapper fnm, String name) {
    if (fnm == null)
      return null;

    int sep = name.indexOf(':');
    if (sep == -1)
      return null;

    String prefix = name.substring(0, sep);
    String localName = name.substring(sep + 1);
    Method method = fnm.resolveFunction(prefix, localName);
    return method == null ? null : new SingleMethodClosure(method);
  }

  public MethodClosure resolveSystemMethod(String name) {
    return builtin.get(name);
  }

  public List<String> listGlobalMethods() {
    return new ArrayList<>(global.map.keySet());
  }

  public List<String> listSystemMethods() {
    return new ArrayList<>(builtin.map.keySet());
  }

  // Implementation -----------------------

  private static boolean is_scope(Method method, ExpandoScope scope) {
    Expando meta = method.getAnnotation(Expando.class);
    if (meta == null)
      return scope == ExpandoScope.GLOBAL;

    ExpandoScope[] scopes = meta.scope();
    if (scopes.length == 0) {
      return scope == ExpandoScope.EXPANDO;
    } else {
      for (ExpandoScope s : scopes)
        if (s == scope)
          return true;
      return false;
    }
  }

  static class MethodMap {
    final Map<String, JavaMethodClosure> map = new HashMap<>();

    public MethodClosure get(String name) {
      return map.get(name);
    }

    public void addAll(Class<?> baseClass) {
      for (Method method : baseClass.getMethods()) {
        if (!Modifier.isStatic(method.getModifiers())) {
          add(method, null);
        }
      }
    }

    public void addAllProtected(Class<?> baseClass) {
      List<Method> lst = new ArrayList<>();
      for (Class<?> c = baseClass; c != null; c = c.getSuperclass()) {
        for (Method method : c.getDeclaredMethods()) {
          int mod = method.getModifiers();
          if ((Modifier.isPublic(mod) || Modifier.isProtected(mod)) &&
              !Modifier.isStatic(mod)) {
            boolean found = false;
            for (Method m : lst) {
              if (identical(method, m)) {
                found = true;
                break;
              }
            }
            if (!found) {
              lst.add(method);
              add(method, null);
            }
          }
        }
      }
    }

    private static boolean identical(Method m1, Method m2) {
      return m1.getName().equals(m2.getName()) &&
             Arrays.equals(m1.getParameterTypes(), m2.getParameterTypes());
    }

    public void addAllStatic(Class<?> baseClass, String prefix) {
      for (Method method : baseClass.getMethods()) {
        if (Modifier.isStatic(method.getModifiers()))
          add(method, prefix);
      }
    }

    public void add(Method method, String prefix) {
      Expando meta = method.getAnnotation(Expando.class);
      if (meta != null) {
        for (String name : meta.name())
          add(method, name, prefix);
      }

      String name = method.getName();
      if (!name.startsWith("__")) {
        if (name.startsWith("_"))
          name = name.substring(1);
        add(method, name, prefix);
      }
    }

    private void add(Method method, String name, String prefix) {
      if (prefix != null)
        name = prefix + ":" + name;
      map.compute(name, (k, c) -> (c == null) ? new SingleMethodClosure(method)
                                              : c.addMethod(method));
    }
  }

  static class ClassMethodMap extends MethodMap {
    @Override
    public void add(Method method, String prefix) {
      if (!is_scope(method, ExpandoScope.EXPANDO))
        super.add(method, prefix);
    }
  }

  static class GlobalMethodMap extends MethodMap {
    final ExpandoMethodMap expandoMap = new ExpandoMethodMap();

    @Override
    public void add(Method method, String prefix) {
      int mods = method.getModifiers();
      if (Modifier.isPublic(mods) && Modifier.isStatic(mods)) {
        if (is_scope(method, ExpandoScope.EXPANDO))
          expandoMap.add(method);
        if (is_scope(method, ExpandoScope.GLOBAL))
          super.add(method, prefix);
      }
    }

    public MethodClosure getExpandoMethod(Class<?> baseClass, String name) {
      return expandoMap.get(baseClass, name);
    }
  }

  static class ExpandoMethodMap {
    final Map<String, SortedSet<ExpandoMethodClosure>> map = new HashMap<>();

    // most concrete class ordered before most general class
    private static final Comparator<ExpandoMethodClosure> order = (m1, m2) -> {
      Class<?> c1 = m1.getTarget();
      Class<?> c2 = m2.getTarget();
      if (c1 == c2)
        return 0;
      return (c1.isAssignableFrom(c2)) ? 1 : -1;
    };

    public void add(Method method) {
      for (String name : method.getAnnotation(Expando.class).name())
        add(method, name);

      String name = method.getName();
      if (!name.startsWith("__"))
        add(method, method.getName());
    }

    private void add(Method method, String name) {
      Class<?>[] params = method.getParameterTypes();
      Class<?> target = (params[0] != ELContext.class) ? params[0] : params[1];

      SortedSet<ExpandoMethodClosure> methods =
        map.computeIfAbsent(name, k -> new TreeSet<>(order));

      for (ExpandoMethodClosure expando : methods) {
        if (target == expando.getTarget()) {
          expando.addMethod(method);
          return;
        }
      }

      Closure delegate = new SingleMethodClosure(method);
      methods.add(new ExpandoMethodClosure(name, target, delegate));
    }

    public void add(ExpandoMethodClosure expando) {
      String name = expando.getName();
      SortedSet<ExpandoMethodClosure> methods =
        map.computeIfAbsent(name, k -> new TreeSet<>(order));
      methods.remove(expando);
      methods.add(expando);
    }

    public MethodClosure get(Class<?> cls, String name) {
      SortedSet<ExpandoMethodClosure> methods = map.get(name);
      if (methods != null) {
        for (ExpandoMethodClosure m : methods) {
          if (m.getTarget().isAssignableFrom(cls))
            return m;
        }
      }
      return null;
    }
  }

  private static final GlobalMethodMap builtin = new GlobalMethodMap();

  static {
    builtin.addAllStatic(elite.lang.Builtin.class, null);
  }

  private final SimpleCache<Class<?>, MethodMap> cache = SimpleCache.make(200);
  private final SimpleCache<Class<?>, MethodMap> pcache = SimpleCache.make(200);
  private final SimpleCache<Class<?>, MethodMap> scache = SimpleCache.make(200);

  private MethodClosure getMethodClosure(Class<?> baseClass, String name) {
    MethodClosure c = global.getExpandoMethod(baseClass, name);
    if (c != null)
      return c;

    MethodMap mmap = cache.get(baseClass);
    if (mmap == null) {
      mmap = new MethodMap();
      mmap.addAll(baseClass);
      cache.put(baseClass, mmap);
    }
    if ((c = mmap.get(name)) != null)
      return c;

    return builtin.getExpandoMethod(baseClass, name);
  }

  private MethodClosure getProtectedMethodClosure(Class<?> baseClass,
                                                  String name) {
    MethodMap mmap = pcache.get(baseClass);
    if (mmap == null) {
      mmap = new MethodMap();
      mmap.addAllProtected(baseClass);
      pcache.put(baseClass, mmap);
    }
    return mmap.get(name);
  }

  private MethodClosure getStaticMethodClosure(Class<?> baseClass,
                                               String name) {
    MethodMap smap = scache.get(baseClass);
    if (smap == null) {
      smap = new ClassMethodMap();
      smap.addAllStatic(baseClass, null);
      scache.put(baseClass, smap);
    }
    return smap.get(name);
  }
}
