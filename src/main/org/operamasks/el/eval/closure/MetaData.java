/*
 * $Id: MetaData.java,v 1.2 2009/05/09 18:51:21 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.eval.closure;

import java.io.Serializable;
import java.util.Arrays;
import elite.lang.Annotation;

public final class MetaData implements Serializable
{
    private Annotation[] annotations;
    private int modifiers;

    private static final long serialVersionUID = -1656409680539962167L;

    public MetaData(Annotation[] annotations, int modifiers) {
        this.annotations = annotations;
        this.modifiers = modifiers;
    }

    public boolean isAnnotationPresent(String type) {
        for (Annotation a : annotations) {
            if (type.equals(a.getAnnotationType())) {
                return true;
            }
        }
        return false;
    }

    public Annotation getAnnotation(String type) {
        for (Annotation a : annotations) {
            if (type.equals(a.getAnnotationType())) {
                return a;
            }
        }
        return null;
    }

    public Annotation[] getAnnotations() {
        return annotations.clone();
    }

    public void addAnnotation(Annotation annotation) {
        Annotation[] t = new Annotation[annotations.length+1];
        System.arraycopy(annotations, 0, t, 0, annotations.length);
        t[annotations.length] = annotation;
        annotations = t;
    }

    public void removeAnnotation(String type) {
        int len = annotations.length;
        for (int i = 0; i < len; ) {
            if (type.equals(annotations[i].getAnnotationType())) {
                System.arraycopy(annotations, i+1, annotations, i, len-i-1);
                len--;
            } else {
                i--;
            }
        }

        if (len != annotations.length) {
            Annotation[] t = new Annotation[len];
            System.arraycopy(annotations, 0, t, 0, len);
            annotations = t;
        }
    }

    public int getModifiers() {
        return modifiers;
    }

    public void setModifiers(int modifiers) {
        this.modifiers = modifiers;
    }
    
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof MetaData) {
            MetaData other = (MetaData)obj;
            return Arrays.equals(annotations, other.annotations) && modifiers == other.modifiers;
        } else {
            return false;
        }
    }

    public int hashCode() {
        int h = modifiers;
        for (Annotation a : annotations)
            h = 31*h + a.hashCode();
        return h;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder("[");
        for (int i = 0; i < annotations.length; i++) {
            if (i != 0) buf.append(",");
            buf.append(annotations[i]);
        }
        buf.append("]");
        return buf.toString();
    }
}
