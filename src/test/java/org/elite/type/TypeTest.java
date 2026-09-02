package org.elite.type;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeTest {

  @Test
  void primitiveNames() {
    assertEquals("Int", PrimitiveType.of(Integer.class).toString());
    assertEquals("Int", PrimitiveType.of(int.class).toString());
    assertEquals("Char", PrimitiveType.of(Character.class).toString());
    assertEquals("BigInt", PrimitiveType.of(BigInteger.class).toString());
    assertEquals("Long", PrimitiveType.of(Long.class).toString());
    assertEquals("Double", PrimitiveType.of(Double.class).toString());
    assertEquals("void", PrimitiveType.of(void.class).toString());
  }

  @Test
  void primitiveEquality() {
    assertEquals(PrimitiveType.of(Integer.class), PrimitiveType.of(int.class));
    assertNotEquals(PrimitiveType.of(Integer.class), PrimitiveType.of(Long.class));
    assertNotEquals(PrimitiveType.of(Integer.class), PrimitiveType.ANY_INT);
    assertEquals(PrimitiveType.ANY_INT, PrimitiveType.ANY_INT);
  }

  @Test
  void anyIntAndAnyFloat() {
    assertEquals("AnyInt", PrimitiveType.ANY_INT.toString());
    assertEquals("AnyFloat", PrimitiveType.ANY_FLOAT.toString());
    assertTrue(PrimitiveType.ANY_INT.isAnyInt());
    assertFalse(PrimitiveType.ANY_INT.isAnyFloat());
    assertNotEquals(PrimitiveType.ANY_INT, PrimitiveType.ANY_FLOAT);
  }

  @Test
  void compositeTypes() {
    Type t = new ListType(PrimitiveType.of(Integer.class));
    assertEquals("List[Int]", t.toString());
    assertEquals(t, new ListType(PrimitiveType.of(int.class)));

    assertEquals("Tuple[String, Int]",
      new TupleType(new Type[]{ PrimitiveType.of(String.class),
                                PrimitiveType.of(Integer.class) }).toString());

    assertEquals("Map[String, Int]",
      new MapType(PrimitiveType.of(String.class),
                  PrimitiveType.of(Integer.class)).toString());

    assertEquals("Array[Int, 2]",
      new ArrayType(PrimitiveType.of(Integer.class), 2).toString());

    assertEquals("Function[Int, [Int, Int]]",
      new FunctionType(PrimitiveType.of(Integer.class),
        new Type[]{ PrimitiveType.of(Integer.class),
                    PrimitiveType.of(Integer.class) }).toString());
  }

  @Test
  void anyType() {
    assertEquals("Any", AnyType.ANY.toString());
    assertEquals(AnyType.ANY, AnyType.ANY);
  }

  @Test
  void errorType() {
    assertEquals("Error", ErrorType.ERROR.toString());
    assertEquals(ErrorType.ERROR, ErrorType.ERROR);
    assertNotEquals(ErrorType.ERROR, AnyType.ANY);
  }

  @Test
  void javaReflectiveTypes() throws Exception {
    java.lang.reflect.Method println = java.io.PrintStream.class
      .getMethod("println", String.class);
    Type t = new JavaMethodType(println);
    assertEquals("JavaMethod[void PrintStream.println(String)]", t.toString());
    assertEquals(t, new JavaMethodType(println));

    java.lang.reflect.Field out = System.class.getField("out");
    Type f = new JavaFieldType(out);
    assertEquals("JavaField[PrintStream]", f.toString());
    assertEquals(f, new JavaFieldType(out));

    java.lang.reflect.Constructor<?> ctor =
      java.util.ArrayList.class.getConstructor();
    Type c = new JavaConstructorType(ctor);
    assertEquals("JavaConstructor[ArrayList()]", c.toString());
    assertEquals(c, new JavaConstructorType(ctor));
    assertNotEquals(c, t);
  }

  @Test
  void javaConstructorWithParams() throws Exception {
    java.lang.reflect.Constructor<?> ctor =
      java.awt.Point.class.getConstructor(int.class, int.class);
    assertEquals("JavaConstructor[Point(int, int)]",
      new JavaConstructorType(ctor).toString());
  }
}
