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
}
