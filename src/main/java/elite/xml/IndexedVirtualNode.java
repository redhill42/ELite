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
