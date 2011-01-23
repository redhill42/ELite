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

package org.operamasks.util;

import org.w3c.dom.*;
import java.io.Writer;
import java.io.IOException;

public class DOMWriter extends XmlWriter
{
    public DOMWriter(Writer out) {
	super(out);
    }

    public DOMWriter(Writer out, int step) {
	super(out, step);
    }

    public void writeNode(Node node)
	throws IOException
    {
	switch (node.getNodeType()) {
	case Node.DOCUMENT_NODE:
        case Node.DOCUMENT_FRAGMENT_NODE:
            writeDocument(node);
	    break;

	case Node.DOCUMENT_TYPE_NODE:
	    writeDocType((DocumentType)node);
	    break;

	case Node.ELEMENT_NODE:
	    writeElement((Element)node);
	    break;

	case Node.ATTRIBUTE_NODE:
	    writeAttribute((Attr)node);
	    break;

	case Node.TEXT_NODE:
	    writeText((Text)node);
	    break;

	case Node.CDATA_SECTION_NODE:
	    writeCDATA((CDATASection)node);
	    break;

	case Node.PROCESSING_INSTRUCTION_NODE:
	    writeProcessingInstruction((ProcessingInstruction)node);
	    break;

	case Node.COMMENT_NODE:
	    writeComment((Comment)node);
	    break;

	case Node.ENTITY_REFERENCE_NODE:
	    writeEntityReference((EntityReference)node);
	    break;
	}
    }

    private void writeDocument(Node doc)
	throws IOException
    {
	Node node = doc.getFirstChild();
	while (node != null) {
	    writeNode(node);
	    writeln();
	    node = node.getNextSibling();
	}
    }

    private void writeDocType(DocumentType doctype)
	throws IOException
    {
	super.writeDocType(doctype.getName(),
			   doctype.getPublicId(),
			   doctype.getSystemId());
    }

    private void writeElement(Element element)
	throws IOException
    {
	writeStartTag(element.getNodeName());

	NamedNodeMap attributes = element.getAttributes();
	int length = attributes.getLength();
	for (int i = 0; i < length; i++) {
	    Node attr = attributes.item(i);
	    writeNode(attr);
	}

	Node kid = element.getFirstChild();
	while (kid != null) {
	    writeNode(kid);
	    kid = kid.getNextSibling();
	}

	writeEndTag(element.getNodeName());
    }

    private void writeAttribute(Attr attr)
	throws IOException
    {
	super.writeAttribute(attr.getNodeName(), attr.getNodeValue());
    }

    private void writeText(Text text)
	throws IOException
    {
	super.writeText(text.getNodeValue());
    }

    private void writeCDATA(CDATASection data)
	throws IOException
    {
	super.writeCDATA(data.getNodeValue());
    }

    private void writeProcessingInstruction(ProcessingInstruction pi)
	throws IOException
    {
	super.writeProcessingInstruction(pi.getTarget(), pi.getData());
    }

    private void writeComment(Comment comment)
	throws IOException
    {
	super.writeComment(comment.getNodeValue());
    }

    private void writeEntityReference(EntityReference ref)
	throws IOException
    {
	write('&');
	write(ref.getNodeName());
	write(';');
    }
}
