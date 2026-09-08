package org.elite.type;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeTest {

  @Test
  void primitiveNames() {
    assertEquals("Int", Type.of(Integer.class).toString());
    assertEquals("Int", Type.of(int.class).toString());
    assertEquals("Char", Type.of(Character.class).toString());
    assertEquals("BigInt", Type.of(BigInteger.class).toString());
    assertEquals("Long", Type.of(Long.class).toString());
    assertEquals("Double", Type.of(Double.class).toString());
    assertEquals("void", Type.of(void.class).toString());
  }

  @Test
  void primitiveEquality() {
    assertEquals(Type.of(Integer.class), Type.of(int.class));
    assertNotEquals(Type.of(Integer.class), Type.of(Long.class));
    assertNotEquals(Type.of(Integer.class), Types.ANY_INT);
    assertEquals(Types.ANY_INT, Types.ANY_INT);
  }

  @Test
  void anyIntAndAnyFloat() {
    assertEquals("AnyInt", Types.ANY_INT.toString());
    assertEquals("AnyFloat", Types.ANY_FLOAT.toString());
    assertNotEquals(Types.ANY_INT, Types.ANY_FLOAT);
  }

  @Test
  void compositeTypes() {
    Type t = new ListType(Type.of(Integer.class));
    assertEquals("List[Int]", t.toString());
    assertEquals(t, new ListType(Type.of(int.class)));

    assertEquals("Tuple[String, Int]",
      new TupleType(new Type[]{ Type.of(String.class),
                                Type.of(Integer.class) }).toString());

    assertEquals("Map[String, Int]",
      new MapType(Type.of(String.class),
                  Type.of(Integer.class)).toString());

    assertEquals("Int[][]",
      new ArrayType(Type.of(Integer.class), 2).toString());

    assertEquals("(Int,Int)->Int",
      new FunctionType(Type.of(Integer.class),
                       new Type[]{ Type.of(Integer.class),
                    Type.of(Integer.class) }).toString());
  }

  @Test
  void anyType() {
    assertEquals("Any", Types.ANY.toString());
    assertEquals(Types.ANY, Types.ANY);
  }

  @Test
  void errorType() {
    assertEquals("Error", Types.ERROR.toString());
    assertEquals(Types.ERROR, Types.ERROR);
    assertNotEquals(Types.ERROR, Types.ANY);
  }
}
