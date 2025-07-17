## 编译器前端文档

本文档详细介绍了 `Compiler2025-RealARM` 项目的前端部分，涵盖了从词法分析、语法分析到抽象语法树（AST）生成的完整流程。

### 1. 概述

编译器前端的核心任务是将人类可读的 SysY 源程序代码转换为一种结构化的、适合后端处理的中间表示——抽象语法树（AST）。这个过程主要分为两个阶段：

1.  **词法分析 (Lexical Analysis)**：将源代码文本分解成一系列有意义的最小单元，称为 **词法单元 (Token)**。
2.  **语法分析 (Syntax Analysis)**：根据语言的语法规则，将词法单元流组合成一个层次化的树形结构，即 **AST**。

### 2. 核心组件

前端由以下几个关键的 Java 类组成，它们位于 `src/Frontend` 包下：

-   `SysYLexer`: 词法分析器。
-   `SysYToken` & `SysYTokenType`: 词法单元的定义。
-   `TokenStream`: 词法单元流，为语法分析器提供服务。
-   `SysYParser`: 语法分析器。
-   `SyntaxTree`: 抽象语法树（AST）的节点定义。

---

### 2.1. 词法分析 (`SysYLexer`)

`SysYLexer` 负责读取 SysY 源代码文件（`.sy`），并将其转换为一个 `TokenStream`。

**工作流程:**

1.  **初始化**: 创建一个 `SysYLexer` 实例，传入源文件的 `Reader`。
2.  **逐个扫描**: 调用 `tokenize()` 方法，该方法会循环调用内部的 `nextToken()` 来逐个识别和生成词法单元。
3.  **处理细节**:
    -   **空白与换行**: 自动跳过所有空白字符（空格、制表符、换行符）。
    -   **注释**: 正确识别并跳过单行注释 (`// ...`) 和多行注释 (`/* ... */`)。
    -   **标识符与关键字**: 识别由字母、数字和下划线组成的标识符。内部使用一个静态 `HashMap<String, SysYTokenType> KEYWORDS` 来高效地将识别出的字符串与关键字进行匹配。
    -   **数字常量**: 支持多种格式的数字常量，通过 `scanNumber()` 方法进行精密解析：
        -   十进制整数 (`123`)
        -   八进制整数 (`0123`)
        -   十六进制整数 (`0x1A`, `0XFF`)
        -   十进制浮点数 (`3.14`, `1.2e-5`)
        -   十六进制浮点数 (`0x1.2p10`)
    -   **字符串常量**: 解析由双引号包围的字符串，并处理其中的转义字符（如 `\n`, `\"`）。
    -   **运算符与分隔符**: 识别如 `+`, `-`, `*`, `/`, `==`, `!=`, `&&`, `||`, `;`, `,`, `(`, `)` 等所有 SysY 语言定义的符号。
4.  **结束**: 当读取到文件末尾时，添加一个特殊的 `EOF` (End of File) 词法单元作为流的结束标记。

---

### 2.2. 词法单元 (`SysYToken`, `SysYTokenType`, `TokenStream`)

这三个类共同定义和管理词法分析的产物。

-   **`SysYTokenType`**: 这是一个枚举 (`enum`) 类型，它定义了 SysY 语言中所有可能的词法单元类型，极大地增强了代码的可读性和类型安全性。所有类型如下：

    | 类别 | 包含的 Token 类型 |
    | :--- | :--- |
    | **标识符** | `IDENTIFIER` |
    | **整型常量** | `INT_CONST`, `DEC_CONST`, `OCT_CONST`, `HEX_CONST` |
    | **浮点常量** | `FLOAT_CONST`, `DEC_FLOAT`, `HEX_FLOAT` |
    | **字符串常量** | `STRING_CONST` |
    | **关键字** | `CONST`, `INT`, `FLOAT`, `VOID`, `IF`, `ELSE`, `WHILE`, `BREAK`, `CONTINUE`, `RETURN` |
    | **算术运算符** | `PLUS`, `MINUS`, `MULTIPLY`, `DIVIDE`, `MODULO` |
    | **关系运算符**| `LESS`, `GREATER`, `LESS_EQUAL`, `GREATER_EQUAL`, `EQUAL`, `NOT_EQUAL` |
    | **逻辑运算符**| `LOGICAL_AND`, `LOGICAL_OR`, `LOGICAL_NOT` |
    | **赋值运算符**| `ASSIGN` |
    | **分隔符** | `SEMICOLON`, `COMMA`, `LEFT_PAREN`, `RIGHT_PAREN`, `LEFT_BRACKET`, `RIGHT_BRACKET`, `LEFT_BRACE`, `RIGHT_BRACE` |
    | **特殊标记** | `EOF` |

-   **`SysYToken`**: 代表一个具体的词法单元。它包含了：
    -   `type`: 该词法单元的类型（`SysYTokenType`）。
    -   `lexeme`: 在源代码中的原始文本，如 `"while"` 或 `"123"`。
    -   `line` / `column`: 该词法单元在源文件中的位置，用于错误报告。
    -   `value`: 一个 `Object`，用于存储常量词法单元的实际数值（如 `Integer` 或 `Float`）。

-   **`TokenStream`**: 一个对 `ArrayList<SysYToken>` 的封装，为语法分析器提供了便捷的导航功能：
    -   `peek()`: 查看当前位置的 Token 但不消费它。
    -   `next()`: 获取当前 Token 并将指针后移一位。
    -   `check(type)`: 检查当前 Token 是否为指定类型。
    -   `expect(type)`: 期望当前 Token 是指定类型，如果是则消费它，否则抛出 `SyntaxException` 异常。这个方法极大地简化了语法分析器中的错误处理。
    -   `backup()`: 回退指针。

---

### 2.3. 语法分析 (`SysYParser`)

`SysYParser` 接收 `TokenStream` 作为输入，并根据 SysY 的语法规则构建出 `SyntaxTree`。它采用的是经典的 **递归下降 (Recursive Descent)** 解析策略。

**核心特性:**

-   **解析入口**: `parseCompilationUnit()` 是顶层方法，它循环调用 `parseTopLevel()` 来解析全局变量声明和函数定义，直到 TokenStream 结束。

-   **区分声明与定义**: `parseTopLevel()` 通过预读（lookahead）来判断当前代码是变量声明还是函数定义。具体来说，当它解析完 `类型 + 标识符` 后，会检查下一个 Token 是否为左括号 `(`。如果是，则进入函数解析流程 (`parseFuncDef`)；否则，进入变量声明解析流程 (`parseVarDecl`)。

-   **递归下降与运算符优先级**: 解析过程由一系列相互递归调用的方法组成，每个方法对应一个语法规则（非终结符）。为了正确处理运算符优先级，表达式的解析遵循以下调用链（优先级由低到高）：
    1.  `parseExpression()` (入口)
    2.  `parseLogicalOr()` (`||`)
    3.  `parseLogicalAnd()` (`&&`)
    4.  `parseEquality()` (`==`, `!=`)
    5.  `parseRelational()` (`<`, `>`, `<=`, `>=`)
    6.  `parseAdd()` (`+`, `-`)
    7.  `parseMul()` (`*`, `/`, `%`)
    8.  `parseUnary()` (一元 `+`, `-`, `!`)
    9.  `parsePrimary()` (处理括号、字面量、变量、函数调用等基础表达式)

-   **常量表达式求值**: 这是一个重要的优化。在解析过程中，如果遇到需要常量表达式的场景（如数组维度 `int a[1+1];` 或常量初始化 `const int a = 2*10;`），解析器会调用 `evalConstExpr()` 方法尝试当场计算出表达式的值。
    -   它内部维护了一个 `constSymbolTable` 用于存储和查找已定义的常量值。
    -   `computeBinaryOp` 和 `computeUnaryOp` 支持对以下运算符进行常量折叠：
        -   **二元**: `+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `&&`, `||`
        -   **一元**: `+`, `-`, `!`

-   **错误处理**: 当代码不符合语法规则时（例如，缺少分号、类型不匹配），`expect()` 等方法会抛出 `SyntaxException`，并附带错误位置信息，从而中断编译并报告错误。

---

### 2.4. 抽象语法树 (`SyntaxTree`)

`SyntaxTree` (AST) 是语法分析的最终产物。它以对象和层次结构的形式，忠实地表示了源代码的结构，并且去除了不必要的语法细节（如括号、分号）。

**设计:**

-   **节点接口**: 定义了几个核心接口作为节点分类的基础：
    -   `Node`: 所有 AST 节点的公共基接口。
    -   `TopLevelDef`: 顶层定义（全局变量或函数）。
    -   `Stmt`: 语句。
    -   `Expr`: 表达式。

-   **具体的 AST 节点类**:

| 节点类别 | 节点名称 | 描述 |
| :--- | :--- | :--- |
| **顶层结构** | `CompilationUnit` | 表示整个源文件，包含一个 `TopLevelDef` 列表 |
| | `FuncDef` | 函数定义 |
| | `VarDecl` | 变量或常量声明 |
| **语句 (Stmt)** | `Block` | 代码块 `{ ... }` |
| | `IfStmt` | if-else 语句 |
| | `WhileStmt` | while 循环语句 |
| | `ReturnStmt` | return 语句 |
| | `AssignStmt` | 赋值语句 |
| | `ExprStmt` | 表达式语句 (如函数调用) |
| | `BreakStmt` | break 语句 |
| | `ContinueStmt` | continue 语句 |
| **表达式 (Expr)** | `BinaryExpr` | 二元运算表达式 (如 `a + b`) |
| | `UnaryExpr` | 一元运算表达式 (如 `-a`) |
| | `CallExpr` | 函数调用表达式 |
| | `LiteralExpr` | 字面量 (如 `123`, `3.14`) |
| | `VarExpr` | 变量引用 |
| | `ArrayAccessExpr` | 数组访问表达式 (如 `a[i]`) |
| | `ArrayInitExpr` | 数组初始化列表 (如 `{1, 2}`) |
| **辅助定义** | `VarDef` | 单个变量的定义（含维度、初始值） |
| | `Param` | 函数参数 |

-   **可读性与调试**: `SyntaxTree` 类提供了一个 `toTreeString()` 的静态方法，可以将任意 AST 节点转换成一个格式化、带缩进的字符串。这对于调试和验证语法分析器的正确性非常有帮助。

### 3. 如何使用前端

`Compiler.java` 是整个编译器的入口。它演示了如何将上述前端组件串联起来。

**命令行选项:**

1.  **默认 (词法分析)**:
    ```shell
    java src/Compiler.java <source_file.sy>
    ```
    这会执行词法分析，并逐行打印出所有识别到的词法单元及其详细信息。

2.  **语法分析检查 (`--parse` 或 `-p`)**:
    ```shell
    java src/Compiler.java --parse <source_file.sy>
    ```
    这会完整地执行词法和语法分析。如果成功，它会打印出解析到的顶层定义数量；如果失败，则会报告语法错误。

3.  **打印 AST (`--ast` 或 `-a`)**:
    ```shell
    java src/Compiler.java --ast <source_file.sy>
    ```
    这会在语法分析成功后，调用 `printSyntaxTree()` 方法，将生成的 AST 以美观、易读的格式打印到控制台。

### 4. 语言规范

本前端的实现严格遵循 `docs/SysY2022语言定义-V1.pdf` 文档中定义的 SysY 语言规范。所有词法和语法规则都以此为基准。
