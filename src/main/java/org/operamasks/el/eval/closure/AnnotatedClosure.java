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
