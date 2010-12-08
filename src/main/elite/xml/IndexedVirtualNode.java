/*
 * $Id: IndexedVirtualNode.java,v 1.2 2009/04/24 15:12:53 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.xml;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import javax.el.ELContext;
import elite.lang.annotation.Expando;

class IndexedVirtualNode extends VirtualNode
{
    protected final int index;
    protected XmlNode real;

    IndexedVirtualNode(XmlNode parent, String name, int index) {
        super(parent, name);
        this.index = index;
    }

    @Override
    protected XmlNode realize(boolean create) {
        if (real == null) {
            // first realize parent node
            XmlNode parent = this.parent.realize(create);
            if (parent == null) {
                return null;
            }

            // does node already exist?
            Element parentNode = (Element)parent.toDOM();
            Node childNode = child(parentNode, index);

            // create node if it doesn't exist!
            if (create && childNode == null) {
                childNode = parentNode.getOwnerDocument().createElement(name);
                parentNode.appendChild(childNode);
            }

            real = XmlNode.valueOf(childNode);
        }

        return real;
    }

    @Expando(name="+=")
    public XmlNode appendChild(ELContext elctx, Object value) {
        Node newnode = coerceToNode(elctx, value);
        if (newnode != null) {
            Node parent = this.parent.realize(true).toDOM();
            Node refnode = this.toDOM();
            if (refnode != null)
                refnode = refnode.getNextSibling();
            parent.insertBefore(newnode, refnode);
        }
        return this;
    }
}
