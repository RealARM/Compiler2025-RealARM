package Frontend;

import Frontend.SyntaxTree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * SysY 语法分析器（第一阶段：只解析顶层 VarDecl / FuncDef 的外部结构）
 */
public class SysYParser {
    private final TokenStream tokens;
    // 添加常量符号表，用于记录常量变量的值
    private final Map<String, Object> constSymbolTable = new HashMap<>();

    public SysYParser(TokenStream tokens) {
        this.tokens = tokens;
    }

    /**
     * 入口：解析整个编译单元
     */
    public CompilationUnit parseCompilationUnit() throws SyntaxException {
        List<TopLevelDef> defs = new ArrayList<>();
        while (tokens.hasMore() && tokens.peek().getType() != SysYTokenType.EOF) {
            defs.add(parseTopLevel());
        }
        return new CompilationUnit(defs);
    }

    /**
     * 判断当前位置是函数定义还是变量声明并解析
     */
    private TopLevelDef parseTopLevel() throws SyntaxException {
        // 先解析可能出现的 const 关键字
        boolean isConst = false;
        if (tokens.check(SysYTokenType.CONST)) {
            isConst = true;
            tokens.next();
        }

        // 基本类型 int / float / void
        SysYToken typeTok = tokens.expectAny(SysYTokenType.INT, SysYTokenType.FLOAT, SysYTokenType.VOID);
        String baseType = typeTok.getLexeme();

        // 读取标识符
        SysYToken identTok = tokens.expect(SysYTokenType.IDENTIFIER);
        String ident = identTok.getLexeme();

        // 判断后面是 "(" → 函数定义，否则是变量声明
        if (tokens.check(SysYTokenType.LEFT_PAREN)) {
            return parseFuncDef(isConst, baseType, ident);
        } else {
            if (isConst) {
                return parseConstDecl(baseType, ident);
            } else {
                return parseVarDecl(isConst, baseType, ident);
            }
        }
    }

    /* -------------------- 常量声明 -------------------- */
    
    private VarDecl parseConstDecl(String baseType, String firstIdent) throws SyntaxException {
        List<VarDef> vars = new ArrayList<>();
        
        // 处理第一个常量定义
        List<Integer> firstDims = new ArrayList<>();
        // 检查是否有数组维度
        while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            tokens.next(); // 消耗左括号
            // 解析维度常量表达式
            Expr dimExpr = parseExpression();
            tokens.expect(SysYTokenType.RIGHT_BRACKET);
            
            // 尝试进行常量折叠
            Object constVal = evalConstExpr(dimExpr);
            if (constVal instanceof Integer) {
                int dimSize = (Integer) constVal;
                if (dimSize < 0) {
                    throw new SyntaxException("数组维度不能为负数，在第 " + tokens.peek().getLine() + " 行");
                }
                firstDims.add(dimSize);
            } else {
                throw new SyntaxException("数组维度必须是常量表达式，在第 " + tokens.peek().getLine() + " 行");
            }
        }
        
        // 常量必须有初始化
        tokens.expect(SysYTokenType.ASSIGN);
        Expr firstInitExpr = parseInitVal();
        
        // 尝试求解常量值并添加到符号表
        Object constValue = evalConstExpr(firstInitExpr);
        if (constValue != null) {
            constSymbolTable.put(firstIdent, constValue);
        }
        
        vars.add(new VarDef(firstIdent, firstDims, firstInitExpr));

        // 可能有其他常量定义: , ident ...
        while (tokens.check(SysYTokenType.COMMA)) {
            tokens.next();
            SysYToken identTok = tokens.expect(SysYTokenType.IDENTIFIER);
            String constName = identTok.getLexeme();
            
            List<Integer> dims = new ArrayList<>();
            // 检查是否有数组维度
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next(); // 消耗左括号
                Expr dimExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                
                // 尝试进行常量折叠
                Object constVal = evalConstExpr(dimExpr);
                if (constVal instanceof Integer) {
                    int dimSize = (Integer) constVal;
                    if (dimSize < 0) {
                        throw new SyntaxException("数组维度不能为负数，在第 " + tokens.peek().getLine() + " 行");
                    }
                    dims.add(dimSize);
                } else {
                    throw new SyntaxException("数组维度必须是常量表达式，在第 " + tokens.peek().getLine() + " 行");
                }
            }
            
            // 常量必须有初始化
            tokens.expect(SysYTokenType.ASSIGN);
            Expr constInitExpr = parseInitVal();
            
            // 尝试求解常量值并添加到符号表
            Object constVarValue = evalConstExpr(constInitExpr);
            if (constVarValue != null) {
                constSymbolTable.put(constName, constVarValue);
            }
            
            vars.add(new VarDef(constName, dims, constInitExpr));
        }
        
        // 结尾分号
        tokens.expect(SysYTokenType.SEMICOLON);
        return new VarDecl(true, baseType, vars);
    }

    /* -------------------- 函数定义 -------------------- */

    private FuncDef parseFuncDef(boolean isConst, String retType, String name) throws SyntaxException {
        if (isConst) {
            throw new SyntaxException("'const' cannot appear before function definition at line " + tokens.peek().getLine());
        }
        tokens.expect(SysYTokenType.LEFT_PAREN);
        List<Param> params = new ArrayList<>();
        if (!tokens.check(SysYTokenType.RIGHT_PAREN)) {
            params.add(parseParam());
            while (tokens.check(SysYTokenType.COMMA)) {
                tokens.next();
                params.add(parseParam());
            }
        }
        tokens.expect(SysYTokenType.RIGHT_PAREN);

        Block body = parseBlock();
        return new FuncDef(retType, name, params, body);
    }

    private Param parseParam() throws SyntaxException {
        SysYToken typeTok = tokens.expectAny(SysYTokenType.INT, SysYTokenType.FLOAT);
        String type = typeTok.getLexeme();
        String paramName = tokens.expect(SysYTokenType.IDENTIFIER).getLexeme();
        boolean isArr = false;
        List<Expr> arrDims = new ArrayList<>();
        
        if (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            isArr = true;
            // 第一维长度省略
            tokens.next();
            tokens.expect(SysYTokenType.RIGHT_BRACKET);
            
            // 其余维度需要有具体长度 [Exp]
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next();
                // 解析数组维度表达式
                Expr dimExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                arrDims.add(dimExpr);
            }
        }
        
        return new Param(type, paramName, isArr, arrDims);
    }

    /* -------------------- Block & Statement -------------------- */

    private Block parseBlock() throws SyntaxException {
        tokens.expect(SysYTokenType.LEFT_BRACE);
        List<Stmt> stmts = new ArrayList<>();
        while (!tokens.check(SysYTokenType.RIGHT_BRACE)) {
            stmts.add(parseStatement());
        }
        tokens.expect(SysYTokenType.RIGHT_BRACE);
        return new Block(stmts);
    }

    private Stmt parseStatement() throws SyntaxException {
        // 变量声明: const/int/float 开头
        if (tokens.checkAny(SysYTokenType.CONST, SysYTokenType.INT, SysYTokenType.FLOAT)) {
            boolean isConst = false;
            if (tokens.check(SysYTokenType.CONST)) { isConst = true; tokens.next(); }
            String bType = tokens.expectAny(SysYTokenType.INT, SysYTokenType.FLOAT).getLexeme();
            String firstIdent = tokens.expect(SysYTokenType.IDENTIFIER).getLexeme();
            
            if (isConst) {
                return parseConstDecl(bType, firstIdent);
            } else {
                return parseVarDecl(isConst, bType, firstIdent);
            }
        }

        if (tokens.check(SysYTokenType.RETURN)) {
            tokens.next();
            SyntaxTree.Expr val = null;
            if (!tokens.check(SysYTokenType.SEMICOLON)) {
                val = parseExpression();
            }
            tokens.expect(SysYTokenType.SEMICOLON);
            return new ReturnStmt(val);
        } else if (tokens.check(SysYTokenType.BREAK)) {
            tokens.next();
            tokens.expect(SysYTokenType.SEMICOLON);
            return new BreakStmt();
        } else if (tokens.check(SysYTokenType.CONTINUE)) {
            tokens.next();
            tokens.expect(SysYTokenType.SEMICOLON);
            return new ContinueStmt();
        } else if (tokens.check(SysYTokenType.IF)) {
            tokens.next();
            tokens.expect(SysYTokenType.LEFT_PAREN);
            Expr cond = parseExpression();
            tokens.expect(SysYTokenType.RIGHT_PAREN);
            Stmt thenBranch = parseStatement();
            Stmt elseBranch = null;
            if (tokens.check(SysYTokenType.ELSE)) {
                tokens.next();
                elseBranch = parseStatement();
            }
            return new IfStmt(cond, thenBranch, elseBranch);
        } else if (tokens.check(SysYTokenType.WHILE)) {
            tokens.next();
            tokens.expect(SysYTokenType.LEFT_PAREN);
            Expr cond = parseExpression();
            tokens.expect(SysYTokenType.RIGHT_PAREN);
            Stmt body = parseStatement();
            return new WhileStmt(cond, body);
        } else if (tokens.check(SysYTokenType.LEFT_BRACE)) {
            return parseBlock();
        } else {
            // 处理赋值 / 表达式 / 空语句
            if (tokens.check(SysYTokenType.SEMICOLON)) {
                tokens.next();
                return new ExprStmt(null);
            }

            // 首先尝试解析表达式
            Expr expr = parseExpression();
            
            // 如果表达式后面跟着赋值符号，那么这是一个赋值语句
            if (tokens.check(SysYTokenType.ASSIGN)) {
                tokens.next(); // 消耗赋值符号
                Expr value = parseExpression();
                tokens.expect(SysYTokenType.SEMICOLON);
                return new AssignStmt(expr, value);
            } else {
                // 否则这是一个表达式语句
                tokens.expect(SysYTokenType.SEMICOLON);
                return new ExprStmt(expr);
            }
        }
    }

    private Expr parseLVal() throws SyntaxException {
        String name = tokens.expect(SysYTokenType.IDENTIFIER).getLexeme();
        
        // 检查是否为数组访问
        if (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            List<Expr> indices = new ArrayList<>();
            
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next(); // 消耗左括号
                Expr indexExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                indices.add(indexExpr);
            }
            
            return new ArrayAccessExpr(name, indices);
        } else {
            return new VarExpr(name);
        }
    }

    /* -------------------- 表达式（按优先级） -------------------- */

    /**
     * 表达式入口，按照 SysY 语言定义应当与 C 语言优先级一致。
     * 最高层为逻辑或表达式 LOrExp。
     */
    private Expr parseExpression() throws SyntaxException {
        return parseLogicalOr();
    }

    /* 优先级从低到高： || -> && -> ==/!= -> < > <= >= -> + - -> * / % -> Unary -> Primary */

    private Expr parseLogicalOr() throws SyntaxException {
        Expr left = parseLogicalAnd();
        while (tokens.check(SysYTokenType.LOGICAL_OR)) {
            String op = tokens.next().getLexeme(); // "||"
            Expr right = parseLogicalAnd();
            left = new BinaryExpr(left, op, right);
        }
        return left;
    }

    private Expr parseLogicalAnd() throws SyntaxException {
        Expr left = parseEquality();
        while (tokens.check(SysYTokenType.LOGICAL_AND)) {
            String op = tokens.next().getLexeme(); // "&&"
            Expr right = parseEquality();
            left = new BinaryExpr(left, op, right);
        }
        return left;
    }

    private Expr parseEquality() throws SyntaxException {
        Expr left = parseRelational();
        while (tokens.checkAny(SysYTokenType.EQUAL, SysYTokenType.NOT_EQUAL)) {
            String op = tokens.next().getLexeme(); // "==" or "!="
            Expr right = parseRelational();
            left = new BinaryExpr(left, op, right);
        }
        return left;
    }

    private Expr parseRelational() throws SyntaxException {
        Expr left = parseAdd();
        while (tokens.checkAny(SysYTokenType.LESS, SysYTokenType.GREATER, SysYTokenType.LESS_EQUAL, SysYTokenType.GREATER_EQUAL)) {
            String op = tokens.next().getLexeme();
            Expr right = parseAdd();
            left = new BinaryExpr(left, op, right);
        }
        return left;
    }

    private Expr parseAdd() throws SyntaxException {
        Expr left = parseMul();
        while (tokens.checkAny(SysYTokenType.PLUS, SysYTokenType.MINUS)) {
            String op = tokens.next().getLexeme();
            Expr right = parseMul();
            left = new BinaryExpr(left, op, right);
        }
        return left;
    }

    private Expr parseMul() throws SyntaxException {
        Expr left = parseUnary();
        while (tokens.checkAny(SysYTokenType.MULTIPLY, SysYTokenType.DIVIDE, SysYTokenType.MODULO)) {
            String op = tokens.next().getLexeme();
            Expr right = parseUnary();
            left = new BinaryExpr(left, op, right);
        }
        return left;
    }

    private Expr parseUnary() throws SyntaxException {
        if (tokens.checkAny(SysYTokenType.PLUS, SysYTokenType.MINUS, SysYTokenType.LOGICAL_NOT)) {
            String op = tokens.next().getLexeme();
            Expr expr = parseUnary();
            return new UnaryExpr(op, expr);
        }
        return parsePrimary();
    }

    private Expr parsePrimary() throws SyntaxException {
        SysYToken tok = tokens.peek();
        switch (tok.getType()) {
            case IDENTIFIER -> {
                tokens.next();
                String name = tok.getLexeme();
                if (tokens.check(SysYTokenType.LEFT_PAREN)) {
                    // 函数调用
                    tokens.next();
                    List<Expr> args = new ArrayList<>();
                    if (!tokens.check(SysYTokenType.RIGHT_PAREN)) {
                        args.add(parseExpression());
                        while (tokens.check(SysYTokenType.COMMA)) {
                            tokens.next();
                            args.add(parseExpression());
                        }
                    }
                    tokens.expect(SysYTokenType.RIGHT_PAREN);
                    return new CallExpr(name, args);
                } else if (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                    // 数组访问 a[exp][exp]...
                    List<Expr> indices = new ArrayList<>();
                    
                    while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                        tokens.next(); // 消耗左括号
                        // 解析数组索引表达式
                        Expr indexExpr = parseExpression();
                        tokens.expect(SysYTokenType.RIGHT_BRACKET);
                        indices.add(indexExpr);
                    }
                    
                    return new ArrayAccessExpr(name, indices);
                } else {
                    return new VarExpr(name);
                }
            }
            case DEC_CONST, OCT_CONST, HEX_CONST -> {
                tokens.next();
                String lex = tok.getLexeme();
                int intVal;
                if (lex.startsWith("0x") || lex.startsWith("0X")) {
                    intVal = Integer.parseInt(lex.substring(2), 16);
                } else if (lex.startsWith("0") && lex.length() > 1) {
                    intVal = Integer.parseInt(lex, 8);
                } else {
                    intVal = Integer.parseInt(lex);
                }
                return new LiteralExpr(intVal);
            }
            case DEC_FLOAT, HEX_FLOAT -> {
                tokens.next();
                return new LiteralExpr(Float.parseFloat(tok.getLexeme()));
            }
            case LEFT_PAREN -> {
                tokens.next();
                Expr inner = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_PAREN);
                return inner;
            }
            default -> throw new SyntaxException("Unexpected token " + tok);
        }
    }

    /* -------------------- 变量声明 -------------------- */

    private VarDecl parseVarDecl(boolean isConst, String baseType, String firstIdent) throws SyntaxException {
        List<VarDef> vars = new ArrayList<>();
        
        // 处理第一个变量定义
        List<Integer> firstDims = new ArrayList<>();
        // 检查是否有数组维度
        while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            tokens.next(); // 消耗左括号
            // 解析维度常量表达式
            Expr dimExpr = parseExpression();
            tokens.expect(SysYTokenType.RIGHT_BRACKET);
            
            // 尝试进行常量折叠
            Object constVal = evalConstExpr(dimExpr);
            if (constVal instanceof Integer) {
                int dimSize = (Integer) constVal;
                if (dimSize < 0) {
                    throw new SyntaxException("数组维度不能为负数，在第 " + tokens.peek().getLine() + " 行");
                }
                firstDims.add(dimSize);
            } else {
                throw new SyntaxException("数组维度必须是常量表达式，在第 " + tokens.peek().getLine() + " 行");
            }
        }
        
        Expr firstInitExpr = null;
        // 检查是否有初始化
        if (tokens.check(SysYTokenType.ASSIGN)) {
            tokens.next(); // 消耗等号
            firstInitExpr = parseInitVal();
        }
        vars.add(new VarDef(firstIdent, firstDims, firstInitExpr));

        // 可能有其他变量: , ident ...
        while (tokens.check(SysYTokenType.COMMA)) {
            tokens.next();
            SysYToken identTok = tokens.expect(SysYTokenType.IDENTIFIER);
            String varName = identTok.getLexeme();
            
            List<Integer> dims = new ArrayList<>();
            // 检查是否有数组维度
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next(); // 消耗左括号
                // 解析维度常量表达式
                Expr dimExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                
                // 同上处理维度表达式
                Object constVal = evalConstExpr(dimExpr);
                if (constVal instanceof Integer) {
                    int dimSize = (Integer) constVal;
                    if (dimSize < 0) {
                        throw new SyntaxException("数组维度不能为负数，在第 " + tokens.peek().getLine() + " 行");
                    }
                    dims.add(dimSize);
                } else {
                    throw new SyntaxException("数组维度必须是常量表达式，在第 " + tokens.peek().getLine() + " 行");
                }
            }
            
            Expr initExpr = null;
            // 检查是否有初始化
            if (tokens.check(SysYTokenType.ASSIGN)) {
                tokens.next(); // 消耗等号
                initExpr = parseInitVal();
            }
            vars.add(new VarDef(varName, dims, initExpr));
        }
        
        // 结尾分号
        tokens.expect(SysYTokenType.SEMICOLON);
        return new VarDecl(isConst, baseType, vars);
    }

    /**
     * 解析变量初始值，可以是普通表达式或数组初始化列表
     */
    private Expr parseInitVal() throws SyntaxException {
        // 检查是否为数组初始化列表 {expr, expr, ...}
        if (tokens.check(SysYTokenType.LEFT_BRACE)) {
            tokens.next(); // 消耗左花括号
            List<Expr> elements = new ArrayList<>();
            
            // 如果不是空列表
            if (!tokens.check(SysYTokenType.RIGHT_BRACE)) {
                // 解析第一个元素
                elements.add(parseInitVal());
                
                // 解析后续元素
                while (tokens.check(SysYTokenType.COMMA)) {
                    tokens.next(); // 消耗逗号
                    elements.add(parseInitVal());
                }
            }
            
            tokens.expect(SysYTokenType.RIGHT_BRACE);
            return new ArrayInitExpr(elements);
        } else {
            // 普通表达式
            return parseExpression();
        }
    }

    /* -------------------- 常量折叠 -------------------- */

    /**
     * 尝试对表达式进行常量折叠，若成功则返回折叠后的常量值，否则返回null
     */
    private Object evalConstExpr(Expr expr) {
        if (expr instanceof LiteralExpr) {
            return ((LiteralExpr) expr).value;
        } else if (expr instanceof VarExpr) {
            // 处理变量引用，从常量符号表中查找
            String varName = ((VarExpr) expr).name;
            return constSymbolTable.get(varName); // 可能为null，表示未找到或非常量
        } else if (expr instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) expr;
            Object leftVal = evalConstExpr(binExpr.left);
            Object rightVal = evalConstExpr(binExpr.right);
            
            // 如果两个操作数都是常量，则计算结果
            if (leftVal != null && rightVal != null) {
                return computeBinaryOp(leftVal, binExpr.op, rightVal);
            }
        } else if (expr instanceof UnaryExpr) {
            UnaryExpr unExpr = (UnaryExpr) expr;
            Object operandVal = evalConstExpr(unExpr.expr);
            
            if (operandVal != null) {
                return computeUnaryOp(unExpr.op, operandVal);
            }
        }
        
        return null; // 无法进行常量折叠
    }

    /**
     * 计算二元运算表达式的值
     */
    private Object computeBinaryOp(Object left, String op, Object right) {
        // 处理整数运算
        if (left instanceof Integer && right instanceof Integer) {
            int leftInt = (Integer) left;
            int rightInt = (Integer) right;
            
            return switch (op) {
                case "+" -> leftInt + rightInt;
                case "-" -> leftInt - rightInt;
                case "*" -> leftInt * rightInt;
                case "/" -> leftInt / rightInt;
                case "%" -> leftInt % rightInt;
                case "==" -> leftInt == rightInt ? 1 : 0;
                case "!=" -> leftInt != rightInt ? 1 : 0;
                case "<" -> leftInt < rightInt ? 1 : 0;
                case "<=" -> leftInt <= rightInt ? 1 : 0;
                case ">" -> leftInt > rightInt ? 1 : 0;
                case ">=" -> leftInt >= rightInt ? 1 : 0;
                case "&&" -> (leftInt != 0 && rightInt != 0) ? 1 : 0;
                case "||" -> (leftInt != 0 || rightInt != 0) ? 1 : 0;
                default -> null;
            };
        }
        // 处理浮点数运算
        else if (left instanceof Float || right instanceof Float) {
            float leftFloat = (left instanceof Float) ? (Float) left : ((Integer) left).floatValue();
            float rightFloat = (right instanceof Float) ? (Float) right : ((Integer) right).floatValue();
            
            return switch (op) {
                case "+" -> leftFloat + rightFloat;
                case "-" -> leftFloat - rightFloat;
                case "*" -> leftFloat * rightFloat;
                case "/" -> leftFloat / rightFloat;
                case "==" -> leftFloat == rightFloat ? 1 : 0;
                case "!=" -> leftFloat != rightFloat ? 1 : 0;
                case "<" -> leftFloat < rightFloat ? 1 : 0;
                case "<=" -> leftFloat <= rightFloat ? 1 : 0;
                case ">" -> leftFloat > rightFloat ? 1 : 0;
                case ">=" -> leftFloat >= rightFloat ? 1 : 0;
                default -> null;
            };
        }
        
        return null;
    }

    /**
     * 计算一元运算表达式的值
     */
    private Object computeUnaryOp(String op, Object operand) {
        if (operand instanceof Integer) {
            int value = (Integer) operand;
            
            return switch (op) {
                case "+" -> value;
                case "-" -> -value;
                case "!" -> value == 0 ? 1 : 0;
                default -> null;
            };
        } else if (operand instanceof Float) {
            float value = (Float) operand;
            
            return switch (op) {
                case "+" -> value;
                case "-" -> -value;
                default -> null;
            };
        }
        
        return null;
    }

    /* -------------------- 工具 -------------------- */
    private void skipUntil(SysYTokenType type) {
        while (!tokens.check(type) && tokens.hasMore()) {
            tokens.next();
        }
    }

    /**
     * 打印解析得到的 SyntaxTree
     */
    public void printSyntaxTree(CompilationUnit unit) {
        if (unit == null) {
            System.out.println("AST is null");
            return;
        }
        System.out.println("====================== AST ======================");
        System.out.println(unit);
        System.out.println("=================================================");
    }
} 