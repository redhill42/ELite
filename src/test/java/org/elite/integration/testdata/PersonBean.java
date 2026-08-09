package org.elite.integration.testdata;

/**
 * Test JavaBean for invokedynamic property access tests.
 * Covers getters, setters, boolean "is" convention, fields, and static members.
 */
@SuppressWarnings("unused")
public class PersonBean {

    // ── Simple properties (standard JavaBean convention) ──

    private String name;
    private int age;
    private boolean active;
    private double salary;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    // boolean uses "is" convention
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public double getSalary() {
        return salary;
    }

    public void setSalary(double salary) {
        this.salary = salary;
    }

    // ── Public field (for direct field access tests) ──

    public String tag;

    // ── Overloaded setter ──

    private Object data;

    public Object getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public void setData(Integer data) {
        this.data = data;
    }

    // ── Static members ──

    private static String species = "Homo sapiens";

    public static String getSpecies() {
        return species;
    }

    public static void setSpecies(String s) {
        species = s;
    }

    // ── Read-only property ──

    private final long id;

    public PersonBean(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public PersonBean() {
        this(0, null);
    }

    public long getId() {
        return id;
    }

    // ── Write-only property ──

    private String secret;

    public void setSecret(String secret) {
        this.secret = secret;
    }

    // ── Helper for assertions ──

    @Override
    public String toString() {
        return "Person{name='" + name + "', age=" + age +
               ", active=" + active + ", salary=" + salary +
               ", tag=" + tag + ", id=" + id + "}";
    }
}
