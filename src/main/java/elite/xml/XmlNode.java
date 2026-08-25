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

package elite.xml;

import org.elite.eval.Coercible;
import org.elite.eval.ELUtils;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.MethodResolvable;
import org.elite.eval.PropertyResolvable;
import org.elite.eval.TypeCoercion;
import org.elite.eval.closure.CallableClosure;
import org.elite.eval.closure.ClosureObject;
import org.elite.ir.DynamicBootstrap;
import org.elite.ir.MetaClass;
import org.elite.ir.MetaMethod;
import org.elite.util.DOMWriter;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;
import javax.el.ELContext;
import javax.el.ELException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * XmlNode是对DOM结点的包装, 提供一种自然的方式操作DOM树.
 */
@SuppressWarnings("unused")
public abstract class XmlNode
  implements PropertyResolvable, MethodResolvable, Coercible {
  private static final String XML_NODE_KEY = "elite.xml.XmlNode";

  /**
   * 将一个DOM结点封装成XmlNode, 对同一个DOM结点, 当调用此方法时每次
   * 都返回同一个XmlNode实例.
   */
  public static XmlNode valueOf(Node dom) {
    if (dom == null) {
      return null;
    } else {
      XmlNode node = (XmlNode)dom.getUserData(XML_NODE_KEY);
      if (node == null) {
        node = new RealNode(dom);
        dom.setUserData(XML_NODE_KEY, node, null);
      }
      return node;
    }
  }

  /**
   * 返回上下文中唯一的文档对象.
   */
  public static Document getContextDocument(ELContext elctx) {
    Document doc = (Document)elctx.getContext(Document.class);
    if (doc == null) {
      try {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        doc = db.newDocument();
        elctx.putContext(Document.class, doc);
      } catch (ParserConfigurationException ex) {
        throw new ELException(ex);
      }
    }
    return doc;
  }

  /**
   * 内部实现, 将一个虚结点转换成一个实结点.
   */
  protected abstract XmlNode realize(boolean create);

  /**
   * 返回实际的DOM结点.
   */
  public abstract Node toDOM();

  /**
   * 返回XML格式的字符串.
   */
  public abstract String toXMLString();

  /**
   * 按照XML格式将一个XmlNode写入到指定的Writer中.
   */
  public void writeTo(Writer out) throws IOException {
    Node node = toDOM();
    if (node != null) {
      DOMWriter dw = new DOMWriter(out);
      dw.writeNode(toDOM());
      dw.flush();
    }
  }

  /**
   * 按照XML格式将一个XmlNode写入到指定的OutputStream中.
   */
  public void writeTo(OutputStream out) throws IOException {
    Node node = toDOM();
    if (node != null) {
      DOMWriter dw = new DOMWriter(new OutputStreamWriter(out));
      dw.writeNode(toDOM());
      dw.flush();
    }
  }

  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    } else if (obj instanceof XmlNode) {
      Node x = toDOM(), y = ((XmlNode)obj).toDOM();
      return x == y || (x != null && x.equals(y));
    } else {
      return false;
    }
  }

  public Object apply_to(ELContext elctx, CallableClosure proc) {
    if (this instanceof Iterable) {
      List<Object> res = new ArrayList<>();
      for (Object e : (Iterable<?>)this) {
        res.add(proc.call_with(elctx, e));
      }
      return res.size() == 0 ? null : res.size() == 1 ? res.get(0) : res;
    } else {
      return proc.call_with(elctx, this);
    }
  }

  // Utilities

  public static boolean canCoerceToNode(ELContext ctx, Object value) {
    if ((value instanceof XmlNode) || (value instanceof Node) ||
        (value instanceof List)) {
      return true;
    } else if (value instanceof ClosureObject) {
      return ((ClosureObject)value).get_closure(ctx, "toXML") != null;
    } else if (value.getClass().isAnnotationPresent(MetaClass.class)) {
      try {
        Method m = value.getClass()
          .getMethod("toXML", EvaluationContext.class, Object[].class);
        MetaMethod meta = m.getAnnotation(MetaMethod.class);
        if (meta != null && meta.arity() == 0)
          return true;
      } catch (NoSuchMethodException ex) { /* fallthrough */ }
      return false;
    } else {
      return false;
    }
  }

  public static Node coerceToNode(ELContext ctx, Object value) {
    if (value == null) {
      return null;
    } else if (value instanceof XmlNode) {
      return ((XmlNode)value).toDOM();
    } else if (value instanceof Node) {
      return (Node)value;
    } else if (value instanceof ClosureObject) {
      Object obj = ((ClosureObject)value).invoke(
        ctx, "toXML", ELUtils.NO_PARAMS);
      return coerceToNode(ctx, obj);
    } else if (value.getClass().isAnnotationPresent(MetaClass.class)) {
      try {
        EvaluationContext env = new EvaluationContext(ctx);
        MethodHandle mh = DynamicBootstrap.dispatchInvoke(
          MethodHandles.lookup(), env, "toXML", false, new String[0], value,
          new Object[0]);
        Object obj = mh.invoke(new EvaluationContext(ctx), value, new Object[0]);
        return coerceToNode(ctx, obj);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable e) {
        throw new EvaluationException(ctx, e);
      }
    } else if (value instanceof Iterable) {
      DocumentFragment frag = getContextDocument(ctx).createDocumentFragment();
      for (Object o : (Iterable<?>)value)
        frag.appendChild(coerceToNode(ctx, o));
      return frag;
    } else {
      String text = TypeCoercion.coerceToString(value);
      return getContextDocument(ctx).createTextNode(text);
    }
  }
}
