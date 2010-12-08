/*
 * $Id: Namespace.java,v 1.1 2009/03/24 07:51:40 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.xml;

import javax.el.ELContext;
import javax.el.MethodNotFoundException;
import javax.el.MethodInfo;

import elite.lang.Closure;
import org.operamasks.el.eval.closure.AnnotatedClosure;
import org.operamasks.el.eval.Coercible;
import org.operamasks.el.eval.EvaluationException;
import static org.operamasks.el.resources.Resources.*;

public class Namespace extends AnnotatedClosure implements Coercible
{
    private String prefix;
    private String uri;

    public Namespace(String prefix, String uri) {
        this.prefix = prefix;
        this.uri = uri;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getUri() {
        return uri;
    }

    public Object getValue(ELContext elctx) {
        return this;
    }

    public void setValue(ELContext elctx, Object value) {
        if (value == null) {
            this.uri = null;
        } else if (value instanceof String) {
            this.uri = (String)value;
        } else if (value instanceof Namespace) {
            this.uri = ((Namespace)value).getUri();
        } else if (value instanceof java.net.URI) {
            this.uri = value.toString();
        } else if (value instanceof java.net.URL) {
            this.uri = value.toString();
        } else {
            throw new EvaluationException(elctx, _T(JSPRT_COERCE_ERROR, value.getClass().getName(), "Namespace"));
        }
    }

    public Class getType(ELContext elctx) {
        return Namespace.class;
    }

    public Class<?> getExpectedType() {
        return Namespace.class;
    }

    public boolean isReadOnly(ELContext elctx) {
        return false;
    }

    public int arity(ELContext elctx) {
        return -1;
    }

    public MethodInfo getMethodInfo(ELContext elctx) {
        return null;
    }

    public Object invoke(ELContext elctx, Closure[] args) {
        throw new MethodNotFoundException();
    }

    public String getExpressionString() {
        return null;
    }

    public boolean isLiteralText() {
        return false;
    }

    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof Namespace) {
            Namespace other = (Namespace)obj;
            return prefix.equals(other.prefix) &&
                   (uri == null ? other.uri == null : uri.equals(other.uri));
        } else {
            return false;
        }
    }

    public int hashCode() {
        int h = prefix.hashCode();
        if (uri != null)
            h += uri.hashCode();
        return h;
    }

    public String toString() {
        return uri;
    }

    public Object coerce(Class type) {
        if (CharSequence.class.isAssignableFrom(type)) {
            return uri;
        } else {
            return null;
        }
    }
}
