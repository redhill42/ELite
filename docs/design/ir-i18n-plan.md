# IR 子系统国际化方案

> 为 6月8日以来新增代码中的硬编码字符串添加国际化支持，沿用项目现有的 `_T(KEY)` 约定。

## 新增消息标识（IR_ 前缀）

### IR 运行时错误

| 标识 | 英文 | 中文 | 参数 |
|------|------|------|------|
| `IR_BYTECODE_COMPILE_FAILED` | Bytecode compile failed | 字节码编译失败 | — |
| `IR_BC_UNHANDLED_OPCODE` | BC unhandled opcode: {0} ({1}) | 字节码未处理的操作码: {0} ({1}) | opcode name, opcode id |
| `IR_FUNCTION_NOT_REGISTERED` | Function not registered: {0} | 函数未注册: {0} | funcId |
| `IR_TYPE_MISMATCH` | Type mismatch: expected {0}, got {1} | 类型不匹配: 期望 {0}, 实际 {1} | expected, actual |
| `IR_FIELD_READ_FROM_NULL` | Cannot read field ''{0}'' from null | 无法从 null 读取字段 ''{0}'' | fieldName |
| `IR_FIELD_NOT_FOUND` | Field not found: {0} on {1} | 字段未找到: {0} 于类型 {1} | fieldName, className |
| `IR_FIELD_ACCESS_ERROR` | Cannot access field: {0} | 无法访问字段: {0} | fieldName |
| `IR_FIELD_WRITE_TO_NULL` | Cannot write field ''{0}'' to null | 无法向 null 写入字段 ''{0}'' | fieldName |
| `IR_CANNOT_ITERATE` | Cannot iterate over: {0} | 无法迭代类型: {0} | className |
| `IR_DYNAMIC_INVOKE_FAILED` | Dynamic invoke failed | 动态调用失败 | — |

### Runtime 操作错误（已有 EL_ 前缀可复用）

| 标识 | 状态 | 说明 |
|------|:----:|------|
| `EL_UNDEFINED_IDENTIFIER` | 已有 | `Undefined identifier: {0}` |
| `EL_PROPERTY_NOT_FOUND` | 已有 | `Property ''{1}'' not found on type {0}` |
| `EL_NULL_OPERAND` | **新增** | `Null operand in {0}` / `{0} 中的空操作数` |
| `EL_CANNOT_COMPARE` | **新增** | `Cannot compare {0} with {1}` / `无法比较 {0} 与 {1}` |

## 修改文件清单

### 1. Resources.java — 添加常量定义

```java
// IR subsystem
public static final String IR_BYTECODE_COMPILE_FAILED = "IR_BYTECODE_COMPILE_FAILED";
public static final String IR_BC_UNHANDLED_OPCODE = "IR_BC_UNHANDLED_OPCODE";
public static final String IR_FUNCTION_NOT_REGISTERED = "IR_FUNCTION_NOT_REGISTERED";
public static final String IR_TYPE_MISMATCH = "IR_TYPE_MISMATCH";
public static final String IR_FIELD_READ_FROM_NULL = "IR_FIELD_READ_FROM_NULL";
public static final String IR_FIELD_NOT_FOUND = "IR_FIELD_NOT_FOUND";
public static final String IR_FIELD_ACCESS_ERROR = "IR_FIELD_ACCESS_ERROR";
public static final String IR_FIELD_WRITE_TO_NULL = "IR_FIELD_WRITE_TO_NULL";
public static final String IR_CANNOT_ITERATE = "IR_CANNOT_ITERATE";
public static final String IR_DYNAMIC_INVOKE_FAILED = "IR_DYNAMIC_INVOKE_FAILED";

// General additions
public static final String EL_NULL_OPERAND = "EL_NULL_OPERAND";
public static final String EL_CANNOT_COMPARE = "EL_CANNOT_COMPARE";
```

### 2. Messages.properties — 添加英文消息

### 3. Messages_zh_CN.properties — 添加中文消息

### 4. 源码文件 — 替换硬编码字符串

| 文件 | 硬编码字符串 → _T() 调用 |
|------|------|
| `IRBytecodeCompiler.java` | `"Bytecode compile failed"` → `_T(IR_BYTECODE_COMPILE_FAILED)` |
| | `"BC unhandled opcode: " + ...` → `_T(IR_BC_UNHANDLED_OPCODE, ...)` |
| | `"Function not registered: " + funcId` → `_T(IR_FUNCTION_NOT_REGISTERED, funcId)` |
| `IRInterpreter.java` | `"Type mismatch: expected " + ...` → `_T(IR_TYPE_MISMATCH, expected, actual)` |
| | `"Cannot read field '" + name + "' from null"` → `_T(IR_FIELD_READ_FROM_NULL, name)` |
| | `"Field not found: " + name + " on " + ...` → `_T(IR_FIELD_NOT_FOUND, name, className)` |
| | `"Cannot access field: " + name` → `_T(IR_FIELD_ACCESS_ERROR, name)` |
| | `"Cannot write field '" + name + "' to null"` → `_T(IR_FIELD_WRITE_TO_NULL, name)` |
| | `"Cannot iterate over: " + className` → `_T(IR_CANNOT_ITERATE, className)` |
| | `"Undefined identifier: " + name` → `_T(EL_UNDEFINED_IDENTIFIER, name)` |
| | `"Property not found: " + ...` → `_T(EL_PROPERTY_NOT_FOUND, className, key)` |
| | `"dynamic invoke failed"` → `_T(IR_DYNAMIC_INVOKE_FAILED)` |
| `Runtime.java` | `"Undefined identifier: " + name` → `_T(EL_UNDEFINED_IDENTIFIER, name)` |
| | `"Property not found: " + key` → `_T(EL_PROPERTY_NOT_FOUND, className, key)` |
| | `"Cannot read field '" + name + "' from null"` → `_T(IR_FIELD_READ_FROM_NULL, name)` |
| | `"Field not found: " + name + " on " + ...` → `_T(IR_FIELD_NOT_FOUND, name, className)` |
| | `"Cannot access field: " + name` → `_T(IR_FIELD_ACCESS_ERROR, name)` |
| | `"Cannot write field '" + name + "' to null"` → `_T(IR_FIELD_WRITE_TO_NULL, name)` |
| | `"Null operand in " + op` → `_T(EL_NULL_OPERAND, op)` |
| | `"Cannot compare " + ...` → `_T(EL_CANNOT_COMPARE, xName, yName)` |
| | `"Type mismatch: expected " + ...` → `_T(IR_TYPE_MISMATCH, expected, actual)` |
| `ELProgram.java` | `"Bytecode compilation failed in strict mode..."` → `_T(IR_STRICT_BYTECODE_FAILED)` |
| | Debug print stays as-is (not user-visible) |
