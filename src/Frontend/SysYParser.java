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
        if (tokens.check(SysYTokenType.RETURN)) {
            tokens.next();
            SyntaxTree.Expr val = null;
            if (!tokens.check(SysYTokenType.SEMICOLON)) {
                val = parseExpression();
            }
            tokens.expect(SysYTokenType.SEMICOLON);
            return new ReturnStmt(val);
        } else if (tokens.check(SysYTokenType.LEFT_BRACE)) {
            return parseBlock();
        } else {
            // 处理表达式或空语句
            if (tokens.check(SysYTokenType.SEMICOLON)) {
                tokens.next();
                return new ExprStmt(null);
            }
            Expr expr = parseExpression();
            tokens.expect(SysYTokenType.SEMICOLON);
            return new ExprStmt(expr);
        }
    }

    /* -------------------- 表达式（简化版本） -------------------- */

    private Expr parseExpression() throws SyntaxException {
        return parseAdd();
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
                return new LiteralExpr(Integer.parseInt(tok.getLexeme().startsWith("0x") || tok.getLexeme().startsWith("0X") ? tok.getLexeme().substring(2), 16 : tok.getLexeme(), tok.getLexeme().startsWith("0") ? 8 : 10));
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
} 