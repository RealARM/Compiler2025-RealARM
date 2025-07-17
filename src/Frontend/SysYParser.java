package Frontend;

import Frontend.SyntaxTree.*;

import java.util.ArrayList;
import java.util.List;

/**
 * SysY 语法分析器（第一阶段：只解析顶层 VarDecl / FuncDef 的外部结构）
 */
public class SysYParser {
    private final TokenStream tokens;

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
            return parseVarDecl(isConst, baseType, ident);
        }
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
        if (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            isArr = true;
            // 忽略第一维长度
            tokens.next();
            tokens.expect(SysYTokenType.RIGHT_BRACKET);
            // 其余维度 [Exp]
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next();
                // 暂简单跳过 Exp 到右括号
                skipUntil(SysYTokenType.RIGHT_BRACKET);
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
            }
        }
        return new Param(type, paramName, isArr);
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
            VarDecl decl = parseVarDecl(isConst, bType, firstIdent);
            return decl; // VarDecl 也实现 Stmt 吗? 若不是, 我们让VarDecl实现Stmt
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
            // lookahead for assignment
            if (tokens.check(SysYTokenType.IDENTIFIER) && tokens.peek(1) != null && tokens.peek(1).getType() == SysYTokenType.ASSIGN) {
                String name = tokens.next().getLexeme();
                tokens.next(); // consume '='
                Expr value = parseExpression();
                tokens.expect(SysYTokenType.SEMICOLON);
                return new AssignStmt(new VarExpr(name), value);
            } else {
                Expr expr = parseExpression();
                tokens.expect(SysYTokenType.SEMICOLON);
                return new ExprStmt(expr);
            }
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
        vars.add(new VarDef(firstIdent, new ArrayList<>(), null));

        // 可能有其他变量: , ident ...
        while (tokens.check(SysYTokenType.COMMA)) {
            tokens.next();
            SysYToken identTok = tokens.expect(SysYTokenType.IDENTIFIER);
            vars.add(new VarDef(identTok.getLexeme(), new ArrayList<>(), null));
        }
        // 结尾分号
        tokens.expect(SysYTokenType.SEMICOLON);
        return new VarDecl(isConst, baseType, vars);
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