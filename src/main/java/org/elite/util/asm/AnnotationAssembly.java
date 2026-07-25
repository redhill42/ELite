package org.elite.util.asm;

import org.objectweb.asm.AnnotationVisitor;

@SuppressWarnings("unused")
public class AnnotationAssembly {
  private final AnnotationAssembly parent;
  private final AnnotationVisitor impl;

  AnnotationAssembly(AnnotationAssembly parent, AnnotationVisitor impl) {
    this.parent = parent;
    this.impl = impl;
  }

  public AnnotationVisitor getImpl() {
    return impl;
  }

  public AnnotationAssembly FIELD(String name, Object value) {
    impl.visit(name, value);
    return this;
  }

  public AnnotationAssembly ENUM(String name, String descriptor, String value) {
    impl.visitEnum(name, descriptor, value);
    return this;
  }

  public AnnotationAssembly ENUM(String name, Class<?> type, String value) {
    impl.visitEnum(name, AsmType.getDescriptor(type), value);
    return this;
  }

  public AnnotationAssembly ANNOTATION(String name, String descriptor) {
    return new AnnotationAssembly(this, impl.visitAnnotation(name, descriptor));
  }

  public AnnotationAssembly ANNOTATION(String name, Class<?> type) {
    return new AnnotationAssembly(
      this, impl.visitAnnotation(name, AsmType.toInternalName(type)));
  }

  public AnnotationAssembly ARRAY(String name) {
    return new AnnotationAssembly(this, impl.visitArray(name));
  }

  public AnnotationAssembly end() {
    impl.visitEnd();
    return parent;
  }
}
