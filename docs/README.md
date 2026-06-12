# ELite Documentation

## 外部文档（用户面向）

| 文档 | 说明 |
|------|------|
| [Getting Started Guide](spec/getting-started.md) | 安装、Hello World、基础语法、REPL、优化级别 |
| [Java Interoperability Guide](spec/java-interop-guide.md) | 调用 Java 方法/字段、导入包、类型映射、@Expando |
| [Standard Library Reference](spec/standard-library.md) | 内置类型（Seq, Rational, Timestamp 等）和模块（math, io, complex 等） |
| [DSL & Grammar Extension Guide](spec/dsl-grammar-guide.md) | `grammar` 关键字、LALR(1) 语法、运算符声明 |
| [Pattern Matching Guide](spec/pattern-matching-guide.md) | `match`/`case`、常量/类型/解构模式、卫语句 |
| [Closure & Lazy Evaluation Guide](spec/closure-guide.md) | 闭包捕获语义、惰性序列、DelayClosure、TCO |
| [ELite Language Specification](spec/ELite-Language-Specification.md) | 完整语言规范 v2.0 |

## 内部设计文档

| 文档 | 说明 |
|------|------|
| [Project Analysis](design/ANALYSIS.md) | 项目架构、代码质量评估、改进路线图（2026-06-09） |
| [IR Opcode Implementation Plan](design/ir-opcode-implementation-plan.md) | IR 操作码实现计划和完成状态 |
| [Bytecode Compiler Gaps](design/bytecode-compiler-gaps.md) | `-O3` 字节码编译器缺陷和待修复项（2026-06-12） |
