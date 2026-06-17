# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test

```bash
mvn package                          # full build + distribution assembly
mvn test                             # run all tests
mvn test -Dtest=ELEngineTest         # run a single test class
mvn test -Dtest=ELEngineTest#testArithmetic  # run a single test method
mvn compile                          # compile only
```

The build produces both a shaded JAR (with cglib/asm relocated) and a distribution zip under `target/elite-1.0-bin/`.

Java 17 is required. Tests use JUnit 4 (`@Test`/`@Before`).

## Running

```bash
cd target/elite-1.0-bin/elite-1.0
bin/elite.sh                  # interactive REPL
bin/elite.sh sample/hello.xel # run a script file
```

## Architecture

ELite is a dynamic, functional JVM language implemented as a **`javax.script` engine** (JSR 223). The codebase has two top-level package namespaces:

- **`elite.*`** — Language runtime types visible to ELite programs: `Seq`, `Symbol`, `Rational`, `Decimal`, `Range`, `Closure`, `Timestamp`, `TimeSpan`, etc. Also the AST node types under `elite.ast.*`.
- **`org.operamasks.el.*`** — The engine implementation: parser, evaluator, type system, resolvers, script engine integration, and shell.

### Pipeline: Source → AST → ELNode → Evaluation

1. **Parsing** (`org.operamasks.el.parser`): `Parser` extends `Scanner` and implements an **operator-precedence parser** that produces `ELNode` trees. `ELNode` is a parse tree node that also implements `javax.el.ValueExpression` — it can be evaluated directly.

2. **Extensible grammar** (`Grammar`, `GrammarParser`, `ParserCombinator`): The language supports **user-defined DSL syntax** via a built-in LALR(1) grammar system. `Grammar` is a serializable parse table; `GrammarParser` compiles grammar definitions into parse tables. `ParserCombinator` wraps a `Grammar` for use as a standalone parser. Built-in grammar-defined syntax libraries live in `src/main/resources/META-INF/script/elite/*.xel` (loaded at engine init).

3. **AST** (`elite.ast.*`): `Expression` is the abstract base with ~20 concrete subtypes (`ApplyExpression`, `InfixExpression`, `LambdaExpression`, `ListExpression`, `MapExpression`, `ConditionalExpression`, etc.). `ExpressionTransformer` provides visitor-pattern traversal. `ELNode.getExpression()` converts the parse tree into a typed AST `Expression`.

4. **Type system** (`org.operamasks.el.types`): **Gradual typing** — statically infer types where possible, falling back to `DynamicType` for unanalyzable code. `Type` is the abstract base; concrete types are `PrimitiveType`, `ClassType`, `FunctionType`, `VarType` (type variables), `TopType`, `BottomType`, and `DynamicType`. `TypeInferrer` performs bidirectional inference; `TypeChecker` runs as a pass between parse and eval phases, checking and persisting type bindings in the `ELContext`.

5. **Evaluation** (`org.operamasks.el.eval`):
   - `ELEngine` — static entry point; holds the global `ExpressionFactoryImpl`, resolver chain, and ELContext listener registry
   - `ELProgram` — compiled program: holds definitions (`List<ELNode>`), expressions, imports, and module references
   - `EvaluationContext` — per-expression evaluation environment; extends `AbstractClosure` and implements `PropertyDelegate`; manages `VariableMapper`/`FunctionMapper` chain and namespace declarations
   - `Frame` — stack frame holding local variable bindings
   - `Control` — `break`, `continue`, `return`, and `escape` are implemented as exceptions (for performance, `fillInStackTrace()` is a no-op)
   - `Coercion` / `TypeCoercion` — type conversion utilities between ELite and Java types

6. **Closures** (`org.operamasks.el.eval.closure`): Rich closure hierarchy for different callable things — `ThisObject` (base for objects with `this`), `AbstractClosure` (base for callables), `MethodClosure`, `FieldClosure`, `LiteralClosure`, `DataClass`, `ClassDefinition`, `Procedure`, `DelayClosure`/`DelayEvalClosure` (lazy evaluation), `DelegatingClosure`, etc.

7. **Resolvers** (`org.operamasks.el.resolver`): `javax.el.ELResolver` chain that resolves property access, method calls, and type coercion for Java interop. Includes `ClassResolver` (static Java members), `MethodResolver` (overload resolution, multi-methods), `BeanPropertyELResolver`, plus resolvers for arrays, lists, maps, sequences, strings, and units of measure.

8. **Sequences** (`org.operamasks.el.eval.seq`): Lazy/persistent sequence library — `EmptySeq`, `Cons`, `DelayCons`/`DelaySeq`, `ArraySeq`, `ListSeq`, `FilteredSeq`, `MappedSeq`, `MappendSeq`, `Map2Seq`, `IteratorSeq`, `PArraySeq` (persistent array). This implements ELite's list/seq semantics including lazy list comprehensions.

### Script Engine Integration

`ELiteScriptEngineFactory` registers ELite as a `javax.script.ScriptEngine` via `META-INF/services/javax.script.ScriptEngineFactory`. `ELiteScriptEngine` implements `Invocable` and `Compilable`. `ELiteCompiledScript` caches compiled `ELProgram` instances.

### Shell / REPL

`org.operamasks.el.shell.Main` is the entry point. `ShellContext` manages interactive state. `ConsoleReader` wraps JLine for line editing with TAB completion (`ELiteCompletor`). Shell commands are extensible via `CommandProvider` / `Command` interfaces.

### XML Support

`elite.xml.*` provides XML literal support with a virtual DOM: `VirtualNode` (abstract base), `RealNode` (wraps a W3C DOM node), and various virtual node types (`FilterVirtualNode`, `IndexedVirtualNode`, `ContainerVirtualNode`, `DescendantVirtualNode`). `XMLLib` provides XPath-like query operations. XML literals in ELite source are parsed by `XMLParser`.

### Utility Classes

`org.operamasks.util` has `BeanUtils`/`BeanProperty` (JavaBean reflection), `SimpleCache` (generic object cache), `DOMWriter`/`XmlWriter` (XML serialization), and `Utils` (miscellaneous helpers).

## Key Design Decisions

- **Control flow as exceptions**: `break`, `continue`, `return`, and `escape` use Java exceptions with `fillInStackTrace()` suppressed for performance. Don't catch `Control` (or its subclasses) in code that evaluates user expressions.
- **ELNode is both parse tree and value**: `ELNode` extends `javax.el.ValueExpression` — it can be evaluated directly without a separate compilation step. `ELProgram` packages multiple ELNodes with imports/definitions for multi-statement programs.
- **Grammar is serializable**: `Grammar` implements `Serializable` so parse tables can be cached/restored. The grammar system (`GrammarParser`) allows ELite programs to define new syntax at runtime.
- **Two POM files**: `pom.xml` is the standalone build. `cloudway-elite.pom.xml` is for embedding ELite as a child module of the Cloudway server project — it uses different dependency versions and a parent POM.
- **Type bindings persist across eval calls**: `TypeInferrer` stores/resumes type bindings in the `ELContext` so the REPL and multi-statement scripts benefit from accumulated type information.

<!-- superpowers-zh:begin (do not edit between these markers) -->
# Superpowers-ZH 中文增强版

本项目已安装 superpowers-zh 技能框架（20 个 skills）。

## 核心规则

1. **收到任务时，先检查是否有匹配的 skill** — 哪怕只有 1% 的可能性也要检查
2. **设计先于编码** — 收到功能需求时，先用 brainstorming skill 做需求分析
3. **测试先于实现** — 写代码前先写测试（TDD）
4. **验证先于完成** — 声称完成前必须运行验证命令

## 可用 Skills

Skills 位于 `.claude/skills/` 目录，每个 skill 有独立的 `SKILL.md` 文件。

- **brainstorming**: 在任何创造性工作之前必须使用此技能——创建功能、构建组件、添加功能或修改行为。在实现之前先探索用户意图、需求和设计。
- **chinese-code-review**: 中文 review 沟通参考——话术模板、分级标注（必须修复/建议修改/仅供参考）、国内团队常见反模式应对。仅在用户显式 /chinese-code-review 时调用，不要根据上下文自动触发。
- **chinese-commit-conventions**: 中文 commit 与 changelog 配置参考——Conventional Commits 中文适配、commitlint/husky/commitizen 中文模板、conventional-changelog 中文配置。仅在用户显式 /chinese-commit-conventions 时调用，不要根据上下文自动触发。
- **chinese-documentation**: 中文文档排版参考——中英文空格、全半角标点、术语保留、链接格式、中文文案排版指北约定。仅在用户显式 /chinese-documentation 时调用，不要根据上下文自动触发。
- **chinese-git-workflow**: 国内 Git 平台配置参考——Gitee、Coding.net、极狐 GitLab、CNB 的 SSH/HTTPS/凭据/CI 接入差异与镜像同步配置。仅在用户显式 /chinese-git-workflow 时调用，不要根据上下文自动触发。
- **dispatching-parallel-agents**: 当面对 2 个以上可以独立进行、无共享状态或顺序依赖的任务时使用
- **executing-plans**: 当你有一份书面实现计划需要在单独的会话中执行，并设有审查检查点时使用
- **finishing-a-development-branch**: 当实现完成、所有测试通过、需要决定如何集成工作时使用——通过提供合并、PR 或清理等结构化选项来引导开发工作的收尾
- **mcp-builder**: MCP 服务器构建方法论 — 系统化构建生产级 MCP 工具，让 AI 助手连接外部能力
- **receiving-code-review**: 收到代码审查反馈后、实施建议之前使用，尤其当反馈不明确或技术上有疑问时——需要技术严谨性和验证，而非敷衍附和或盲目执行
- **requesting-code-review**: 完成任务、实现重要功能或合并前使用，用于验证工作成果是否符合要求
- **subagent-driven-development**: 当在当前会话中执行包含独立任务的实现计划时使用
- **systematic-debugging**: 遇到任何 bug、测试失败或异常行为时使用，在提出修复方案之前执行
- **test-driven-development**: 在实现任何功能或修复 bug 时使用，在编写实现代码之前
- **using-git-worktrees**: 当需要开始与当前工作区隔离的功能开发，或在执行实现计划之前使用——通过原生工具或 git worktree 回退机制确保隔离工作区存在
- **using-superpowers**: 在开始任何对话时使用——确立如何查找和使用技能，要求在任何响应（包括澄清性问题）之前调用 Skill 工具
- **verification-before-completion**: 在宣称工作完成、已修复或测试通过之前使用，在提交或创建 PR 之前——必须运行验证命令并确认输出后才能声称成功；始终用证据支撑断言
- **workflow-runner**: 在 Claude Code / OpenClaw / Cursor 中直接运行 agency-orchestrator YAML 工作流——无需 API key，使用当前会话的 LLM 作为执行引擎。当用户提供 .yaml 工作流文件或要求多角色协作完成任务时触发。
- **writing-plans**: 当你有规格说明或需求用于多步骤任务时使用，在动手写代码之前
- **writing-skills**: 当创建新技能、编辑现有技能或在部署前验证技能是否有效时使用

## 如何使用

当任务匹配某个 skill 时，使用 `Skill` 工具加载对应 skill 并严格遵循其流程。绝不要用 Read 工具读取 SKILL.md 文件。

如果你认为哪怕只有 1% 的可能性某个 skill 适用于你正在做的事情，你必须调用该 skill 检查。
<!-- superpowers-zh:end -->
