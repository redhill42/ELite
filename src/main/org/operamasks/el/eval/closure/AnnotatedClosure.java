/*
 * $Id: AnnotatedClosure.java,v 1.7 2009/05/09 18:51:21 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
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

package org.operamasks.el.eval.closure;

import java.lang.reflect.Modifier;

import elite.lang.Closure;
import elite.lang.Annotation;

public abstract class AnnotatedClosure extends Closure
{
    private MetaData metadata;
    private int modifiers;

    private static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];

    public void setMetaData(MetaData metadata) {
        this.metadata = metadata;
        this.modifiers |= metadata.getModifiers();
    }

    public boolean isAnnotationPresent(String type) {
        return metadata != null && metadata.isAnnotationPresent(type);
    }

    public Annotation getAnnotation(String type) {
        return metadata == null ? null : metadata.getAnnotation(type);
    }

    public Annotation[] getAnnotations() {
        return metadata == null ? EMPTY_ANNOTATIONS : metadata.getAnnotations();
    }

    public void addAnnotation(Annotation annotation) {
        if (metadata == null)
            metadata = new MetaData(EMPTY_ANNOTATIONS, modifiers);
        metadata.addAnnotation(annotation);
    }

    public void removeAnnotation(String type) {
        if (metadata != null) {
            metadata.removeAnnotation(type);
        }
    }

    public int getModifiers() {
        return modifiers;
    }

    public void setModifiers(int modifiers) {
        this.modifiers = modifiers;
        if (metadata != null) {
            metadata.setModifiers(modifiers);
        }
    }

    public boolean isPrivate() {
        return (modifiers & Modifier.PRIVATE) != 0;
    }

    public boolean isProtected() {
        return (modifiers & Modifier.PROTECTED) != 0;
    }

    public boolean isPublic() {
        return (modifiers & (Modifier.PRIVATE|Modifier.PROTECTED)) == 0;
    }

    public boolean isStatic() {
        return (modifiers & Modifier.STATIC) != 0;
    }

    public boolean isAbstract() {
        return (modifiers & Modifier.ABSTRACT) != 0;
    }

    public boolean isFinal() {
        return (modifiers & Modifier.FINAL) != 0;
    }

    public boolean isSynchronized() {
        return (modifiers & Modifier.SYNCHRONIZED) != 0;
    }
}
