/*
 * $Id: DOMWriter.java,v 1.1 2009/03/22 08:37:56 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
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
