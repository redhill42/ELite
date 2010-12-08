/*
 * $Id: XmlWriter.java,v 1.1 2009/03/22 08:37:56 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.util;

import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * XmlWriter is a BufferedWriter subclass that has usefull methods to write
 * various XML constructions. It also supports automatic indentation of lines
 * of text written to the underlying Writer.
 */
public class XmlWriter extends BufferedWriter
{
    /** The underlying Writer. */
    private Writer out;

    /** The character encoder to detect unwritable characters. */
    private CharsetEncoder encoder;

    /** number of spaces to change indent when indenting in or out */
    private int indentStep = 2;

    /** number of spaces to convert into tabs. */
    private int tabSize = 8;

    /** current number of spaces to prepend to lines */
    private int indent = 0;

    /** true if the next character written is the first on a line */
    private boolean beginingOfLine = true;

    /** current state of writer */
    private int state = TEXT;

    private static final int START_TAG = 0;
    private static final int END_TAG   = 1;
    private static final int TEXT      = 2;

    /**
     * Create a new XmlWriter that writes XML text to the given writer.
     */
    public XmlWriter(Writer out) {
	super(out);
	this.out = out;

        if (out instanceof OutputStreamWriter) {
            String encoding = ((OutputStreamWriter)out).getEncoding();
            if (encoding != null && !encoding.equalsIgnoreCase("utf-8")) {
                try {
                    encoder = Charset.forName(encoding).newEncoder();
                } catch (Exception ex) { /*ignored*/ }
            }
        }
    }

    /**
     * Create a new XmlWriter that writes XML text to the given Writer.
     */
    public XmlWriter(Writer out, int step) {
	this(out);
	indentStep = step;
    }

    /**
     * Write a single character.
     */
    public void write(int c) throws IOException {
	checkWrite();
	super.write(c);
    }

    /**
     * Write a portion of an array of characters.
     */
    public void write(char[] cbuf, int off, int len) throws IOException {
	if (len > 0) {
	    checkWrite();
	}
	super.write(cbuf, off, len);
    }

    /**
     * Write a portion of a String.
     */
    public void write(String s, int off, int len) throws IOException {
	if (len > 0) {
	    checkWrite();
	}
	super.write(s, off, len);
    }

    /**
     * Write a line separator. The next character written will be
     * preceded by an indent.
     */
    public void newLine() throws IOException {
	if (indentStep >= 0) {
	    super.newLine();
	    beginingOfLine = true;
	}
    }

    /**
     * Check if an indent needs to be written before writing the next
     * character.
     */
    protected void checkWrite() throws IOException {
	if (indentStep < 0) {
	    return;
	}
	if (beginingOfLine) {
	    beginingOfLine = false;
	    int i = indent;
	    while (i >= tabSize) {
		super.write('\t');
		i -= tabSize;
	    }
	    while (i > 0) {
		super.write(' ');
		--i;
	    }
	}
    }

    /**
     * Increase the current indent by the indent step.
     */
    public void indentPlus() {
	if (indentStep > 0) {
	    indent += indentStep;
	}
    }

    /**
     * Decrease the current indent by the indent step.
     */
    public void indentMinus() {
	if (indentStep > 0) {
	    indent -= indentStep;
	    if (indent < 0)
		indent = 0;
	}
    }

    /**
     * End current line.
     */
    public void writeln() throws IOException {
	newLine();
    }

    /**
     * Write string; end current line.
     */
    public void writeln(String s) throws IOException {
	write(s);
	newLine();
    }

    /**
     * Write the XML declaration.
     */
    public void writeXmlDeclaration(String encoding) throws IOException {
        writeXmlDeclaration("1.0", encoding);
    }

    /**
     * Write the XML declaration.
     */
    public void writeXmlDeclaration(String version, String encoding)
        throws IOException
    {
        if (version == null) {
            version = "1.0";
        }
        if (encoding == null) {
            if (out instanceof OutputStreamWriter)
                encoding = ((OutputStreamWriter)out).getEncoding();
        }

        write("<?xml version=\"");
        write(version);
        write('\"');
        if (encoding != null) {
            write(" encoding=\"");
            write(encoding);
            write('\"');
        }
        write("?>");
        writeln();
        writeln();
    }

    /**
     * Write the document type.
     */
    public void writeDocType(String rootElementName, String publicId, String systemId)
	throws IOException
    {
	write("<!DOCTYPE ");
	write(rootElementName);
	if (systemId != null) {
	    if (publicId != null) {
		write(" PUBLIC '");
		write(publicId);
		write("' '");
	    } else {
		write(" SYSTEM '");
	    }
	    write(systemId);
	    write("'");
	}
	write(">");
	writeln();
    }

    private void finishStartTag() throws IOException {
	if (state == START_TAG) {
	    write('>');
	}
    }

    /**
     * Writes the text, escaping XML metacharacters as needed
     * to let this text be parsed again without change.
     */
    public void writeText(char[] data, int off, int len) throws IOException {
	finishStartTag();
	state = TEXT;

	int start = off, last = off, end = off + len;

	while (last < end) {
	    char c = data[last];

            if ((last + 1) < end && (c >= 0xd800 && c <= 0xdbff)) {
                // surrogate character
                char c2 = data[last+1];
                if (c2 >= 0xdc00 && c2 <= 0xdfff) {
                    int cp = ((c - 0xd800) << 10)
                           + (c2 - 0xdc00) + 0x10000;
                    write(data, start, last - start);
                    write("&#x");
                    write(Integer.toHexString(cp));
                    write(';');
                    start = (last += 2);
                    continue;
                }
            }

            // escape markup delimiters only ... and do bulk
	    // writes wherever possible, for best performance
            if (!canEncode(c)) {
                write(data, start, last - start);
                start = last + 1;
                write("&#x");
                write(Integer.toHexString(c));
                write(';');
            } else if (c == '<') {
                write(data, start, last - start);
                start = last + 1;
                write("&lt;");
            } else if (c == '>') {
                write(data, start, last - start);
                start = last + 1;
                write("&gt;");
            } else if (c == '&') {
                write(data, start, last - start);
                start = last + 1;
                write("&amp;");
            }
	    last++;
	}
	write(data, start, last - start);
    }

    /**
     * Writes the text, escaping XML metacharacters as needed
     * to let this text be parsed again without change.
     */
    public void writeText(String text) throws IOException {
	finishStartTag();
	state = TEXT;

	int len = text.length();
	int start = 0, last = 0;

	while (last < len) {
	    char c = text.charAt(last);

            if ((last + 1) < len && (c >= 0xd800 && c <= 0xdbff)) {
                // surrogate character
                char c2 = text.charAt(last + 1);
                if (c2 >= 0xdc00 && c2 <= 0xdfff) {
                    int cp = ((c - 0xd800) << 10)
                           + (c2 - 0xdc00) + 0x10000;
                    write(text, start, last - start);
                    write("&#x");
                    write(Integer.toHexString(cp));
                    write(';');
                    start = (last += 2);
                    continue;
                }
            }

	    // escape markup delimiters only ... and do bulk
	    // writes wherever possible, for best performance
            if (!canEncode(c)) {
                write(text, start, last - start);
                start = last + 1;
                write("&#x");
                write(Integer.toHexString(c));
                write(';');
            } else if (c == '<') {
                write(text, start, last - start);
                start = last + 1;
                write("&lt;");
            } else if (c == '>') {
                write(text, start, last - start);
                start = last + 1;
                write("&gt;");
            } else if (c == '&') {
                write(text, start, last - start);
                start = last + 1;
                write("&amp;");
            }
	    last++;
	}
	write(text, start, last - start);
    }

    private boolean canEncode(char c) {
        if (encoder != null && !encoder.canEncode(c))
            return false;

        // control characters #x1 through #x1F must appear only as character reference
        if ((c >= 0x0001 && c <= 0x001F) && !(c == '\t' || c == '\n' || c == '\r')) {
            return false;
        }

        // control characters #x7F through #x9F must appear only as character reference
        if ((c >= 0x7F && c <= 0x9F) && (c != 0x85)) {
            return false;
        }

        return true;
    }

    /**
     * Write character data enclosed with <!CDATA[ ... ]]> XML construction.
     */
    public void writeCDATA(char[] data, int off, int len) throws IOException {
	finishStartTag();
	state = TEXT;

	write("<![CDATA[");
	for (int i = off, end = off+len; i < end; i++) {
	    char c = data[i];

	    // embedded "]]>" needs to be split into adjacent
	    // CDATA blocks
	    if (c == ']') {
		if ((i + 2) < end && data[i+1] == ']' && data[i+2] == '>') {
		    write("]]]]><![CDATA[>");
		    i += 2;
		    continue;
		}
	    }
	    write(c);
	}
	write("]]>");
    }

    /**
     * Write character data enclosed with <!CDATA[ ... ]]> XML construction.
     */
    public void writeCDATA(String data) throws IOException {
	finishStartTag();
	state = TEXT;

	int length = data.length();

	write("<![CDATA[");
	for (int i = 0; i < length; i++) {
	    char c = data.charAt(i);

	    // embedded "]]>" needs to be split into adjacent
	    // CDATA blocks
	    if (c == ']') {
		if ((i + 2) < length
			&& data.charAt(i + 1) == ']'
			&& data.charAt(i + 2) == '>')
		{
		    write("]]]]><![CDATA[>");
		    i += 2;
		    continue;
		}
	    }
	    write(c);
	}
	write("]]>");
    }

    /**
     * Write the start tag of an element.
     */
    public void writeStartTag(String tag) throws IOException {
	if (state == START_TAG) {
	    write('>');
	    writeln();
	    indentPlus();
	} else if (state == END_TAG) {
	    writeln();
	}
	write('<');
	write(tag);
	state = START_TAG;
    }

    /**
     * Write the end tag of an element.
     */
    public void writeEndTag(String tag) throws IOException {
	if (state == START_TAG) {
	    write("/>");
	} else {
	    if (state == END_TAG) {
		writeln();
		indentMinus();
	    }
	    write("</");
	    write(tag);
	    write('>');
	}
	state = END_TAG;
    }

    /**
     * Write the name/value pair of an element attribute.
     */
    public void writeAttribute(String name, String value)
	throws IOException
    {
	write(' ');
	write(name);
	write("=\"");

        int len = value.length();
        for (int i = 0; i < len; i++) {
	    char c = value.charAt(i);
	    switch (c) {
	    case '<':  write("&lt;");   break;
	    case '>':  write("&gt;");   break;
	    case '&':  write("&amp;");  break;
	    case '\'': write("&apos;"); break;
	    case '"':  write("&quot;"); break;
            default:
                if ((i + 1) < len && (c >= 0xd800 && c <= 0xdbff)) {
                    // surrogate character
                    char c2 = value.charAt(i+1);
                    if (c2 >= 0xdc00 && c2 <= 0xdfff) {
                        int cp = ((c - 0xd800) << 10)
                               + (c2 - 0xdc00) + 0x10000;
                        write("&#x");
                        write(Integer.toHexString(cp));
                        write(';');
                        i++;
                        continue;
                    }
                }

                if (!canEncode(c)) {
                    write("&#x");
                    write(Integer.toHexString(c));
                    write(';');
                } else {
                    write(c);
                }
                break;
	    }
	}
	write('"');
    }

    /**
     * Write the tagged text.
     */
    public void writeTaggedText(String tag, String text) throws IOException {
	writeStartTag(tag);
	if (text != null)
	    writeText(text);
	writeEndTag(tag);
    }

    /**
     * Write the empty tag.
     */
    public void writeEmptyTag(String tag) throws IOException {
	writeStartTag(tag);
	writeEndTag(tag);
    }

    /**
     * Write the processing instructions.
     */
    public void writeProcessingInstruction(String target, String data)
	throws IOException
    {
	finishStartTag();
	state = TEXT;

	write("<?");
	write(target);
	if (data != null) {
	    write(' ');
	    write(data);
	}
	write("?>");
    }

    /**
     * Writes out the comment.
     */
    public void writeComment(String data) throws IOException {
	finishStartTag();
	state = TEXT;

	write("<!--");
	if (data != null) {
	    boolean sawDash = false;
	    int length = data.length();

	    // "--" illegal in comments, expand it
	    for (int i = 0; i < length; i++) {
		char c = data.charAt(i);
		if (c == '-') {
		    if (sawDash) {
			write(' ');
		    } else {
			sawDash = true;
		    }
		} else {
		    sawDash = false;
		}
		write(c);
	    }
	    if (sawDash)
		write(' ');
	}
	write("-->");
    }
}
