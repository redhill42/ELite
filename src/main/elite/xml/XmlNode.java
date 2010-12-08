/*
 * $Id: XmlNode.java,v 1.6 2009/05/04 08:35:55 jackyzhang Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.xml;

import java.io.Writer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

import javax.el.ELException;
import javax.el.ELContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Node;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.operamasks.el.eval.PropertyResolvable;
import org.operamasks.el.eval.MethodResolvable;
import org.operamasks.el.eval.Coercible;
import org.operamasks.el.eval.ELUtils;
import org.operamasks.el.eval.TypeCoercion;
import org.operamasks.el.eval.closure.ClosureObject;
import org.operamasks.el.eval.closure.Procedure;
import org.operamasks.util.DOMWriter;

/**
 * XmlNode是对DOM结点的包装, 提供一种自然的方式操作DOM树.
 */
public abstract class XmlNode implements PropertyResolvable, MethodResolvable, Coercible
{
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
    public void writeTo(Writer out)
        throws IOException
    {
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
    public void writeTo(OutputStream out)
        throws IOException
    {
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

    public Object apply_to(ELContext elctx, Procedure proc) {
        if (this instanceof Iterable) {
            List<Object> res = new ArrayList<Object>();
            for (Object e : (Iterable)this) {
                res.add(proc.call_with(elctx, e));
            }
            return res.size() == 0 ? null :
                   res.size() == 1 ? res.get(0)
                                   : res;
        } else {
            return proc.call_with(elctx, this);
        }
    }
    
    // Utilities

    public static boolean canCoerceToNode(ELContext ctx, Object value) {
        if ((value instanceof XmlNode) || (value instanceof Node) || (value instanceof List)) {
            return true;
        } else if (value instanceof ClosureObject) {
            return ((ClosureObject)value).get_closure(ctx, "toXML") != null;
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
            Object obj = ((ClosureObject)value).invoke(ctx, "toXML", ELUtils.NO_PARAMS);
            return coerceToNode(ctx, obj);
        } else if (value instanceof Iterable) {
            DocumentFragment frag = getContextDocument(ctx).createDocumentFragment();
            for (Iterator i = ((Iterable)value).iterator(); i.hasNext(); )
                frag.appendChild(coerceToNode(ctx, i.next()));
            return frag;
        } else {
            String text = TypeCoercion.coerceToString(value);
            return getContextDocument(ctx).createTextNode(text);
        }
    }
}
