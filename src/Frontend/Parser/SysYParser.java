package Frontend.Parser;

import Frontend.Lexer.*;
import Frontend.Parser.SyntaxTree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class SysYParser {
    private final TokenStream tokens;
    private final Map<String, Object> constSymbolTable = new HashMap<>();

    public SysYParser(TokenStream tokens) {
        this.tokens = tokens;
    }

    public CompilationUnit parseCompilationUnit() throws SyntaxException {
        List<TopLevelDef> defs = new ArrayList<>();
        while (tokens.hasMore() && tokens.peek().getType() != SysYTokenType.EOF) {
            defs.add(parseTopLevel());
        }
        return new CompilationUnit(defs);
    }

    private TopLevelDef parseTopLevel() throws SyntaxException {
        boolean isConst = false;
        if (tokens.check(SysYTokenType.CONST)) {
            isConst = true;
            tokens.next();
        }

        SysYToken typeTok = tokens.expectAny(SysYTokenType.INT, SysYTokenType.FLOAT, SysYTokenType.VOID);
        String baseType = typeTok.getLexeme();

        SysYToken identTok = tokens.expect(SysYTokenType.IDENTIFIER);
        String ident = identTok.getLexeme();

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


    
    private VarDecl parseConstDecl(String baseType, String firstIdent) throws SyntaxException {
        List<VarDef> vars = new ArrayList<>();
        
        // 处理第一个常量定义
        List<Integer> firstDims = new ArrayList<>();
        // 检查是否有数组维度
        while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            tokens.next();

            Expr dimExpr = parseExpression();
            tokens.expect(SysYTokenType.RIGHT_BRACKET);
            

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
        

        tokens.expect(SysYTokenType.ASSIGN);
        Expr firstInitExpr = parseInitVal();
        

        Object constValue = evalConstExpr(firstInitExpr);
        if (constValue != null) {
            constSymbolTable.put(firstIdent, constValue);
        }
        
        vars.add(new VarDef(firstIdent, firstDims, firstInitExpr));


        while (tokens.check(SysYTokenType.COMMA)) {
            tokens.next();
            SysYToken identTok = tokens.expect(SysYTokenType.IDENTIFIER);
            String constName = identTok.getLexeme();
            
            List<Integer> dims = new ArrayList<>();
            // 检查是否有数组维度
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next();
                Expr dimExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                
    
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
            
    
            tokens.expect(SysYTokenType.ASSIGN);
            Expr constInitExpr = parseInitVal();
            
    
            Object constVarValue = evalConstExpr(constInitExpr);
            if (constVarValue != null) {
                constSymbolTable.put(constName, constVarValue);
            }
            
            vars.add(new VarDef(constName, dims, constInitExpr));
        }
        

        tokens.expect(SysYTokenType.SEMICOLON);
        return new VarDecl(true, baseType, vars);
    }



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

            tokens.next();
            tokens.expect(SysYTokenType.RIGHT_BRACKET);
            

            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next();

                Expr dimExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                arrDims.add(dimExpr);
            }
        }
        
        return new Param(type, paramName, isArr, arrDims);
    }



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
            if (tokens.check(SysYTokenType.SEMICOLON)) {
                tokens.next();
                return new ExprStmt(null);
            }

            Expr expr = parseExpression();
            
            if (tokens.check(SysYTokenType.ASSIGN)) {
                tokens.next();
                Expr value = parseExpression();
                tokens.expect(SysYTokenType.SEMICOLON);
                return new AssignStmt(expr, value);
            } else {
                tokens.expect(SysYTokenType.SEMICOLON);
                return new ExprStmt(expr);
            }
        }
    }

    private Expr parseLVal() throws SyntaxException {
        String name = tokens.expect(SysYTokenType.IDENTIFIER).getLexeme();
        

        if (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            List<Expr> indices = new ArrayList<>();
            
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next();
                Expr indexExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                indices.add(indexExpr);
            }
            
            return new ArrayAccessExpr(name, indices);
        } else {
            return new VarExpr(name);
        }
    }

   
    private Expr parseExpression() throws SyntaxException {
        return parseLogicalOr();
    }


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
                        tokens.next();
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
        

        List<Integer> firstDims = new ArrayList<>();
        // 检查是否有数组维度
        while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
            tokens.next();

            Expr dimExpr = parseExpression();
            tokens.expect(SysYTokenType.RIGHT_BRACKET);
            

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

        if (tokens.check(SysYTokenType.ASSIGN)) {
            tokens.next();
            firstInitExpr = parseInitVal();
        }
        vars.add(new VarDef(firstIdent, firstDims, firstInitExpr));


        while (tokens.check(SysYTokenType.COMMA)) {
            tokens.next();
            SysYToken identTok = tokens.expect(SysYTokenType.IDENTIFIER);
            String varName = identTok.getLexeme();
            
            List<Integer> dims = new ArrayList<>();
            // 检查是否有数组维度
            while (tokens.check(SysYTokenType.LEFT_BRACKET)) {
                tokens.next();
    
                Expr dimExpr = parseExpression();
                tokens.expect(SysYTokenType.RIGHT_BRACKET);
                

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
    
            if (tokens.check(SysYTokenType.ASSIGN)) {
                tokens.next();
                initExpr = parseInitVal();
            }
            vars.add(new VarDef(varName, dims, initExpr));
        }
        

        tokens.expect(SysYTokenType.SEMICOLON);
        return new VarDecl(isConst, baseType, vars);
    }

    /**
     * 解析变量初始值，可以是普通表达式或数组初始化列表
     */
    private Expr parseInitVal() throws SyntaxException {
        // 检查是否为数组初始化列表 {expr, expr, ...}
        if (tokens.check(SysYTokenType.LEFT_BRACE)) {
            tokens.next();
            List<Expr> elements = new ArrayList<>();
            

            if (!tokens.check(SysYTokenType.RIGHT_BRACE)) {

                elements.add(parseInitVal());
                

                while (tokens.check(SysYTokenType.COMMA)) {
                    tokens.next();
                    elements.add(parseInitVal());
                }
            }
            
            tokens.expect(SysYTokenType.RIGHT_BRACE);
            return new ArrayInitExpr(elements);
        } else {

            return parseExpression();
        }
    }

   

    /**
     * 尝试对表达式进行常量折叠，若成功则返回折叠后的常量值，否则返回null
     */
    private Object evalConstExpr(Expr expr) {
        if (expr instanceof LiteralExpr) {
            return ((LiteralExpr) expr).value;
        } else if (expr instanceof VarExpr) {

            String varName = ((VarExpr) expr).name;
            return constSymbolTable.get(varName);
        } else if (expr instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) expr;
            Object leftVal = evalConstExpr(binExpr.left);
            Object rightVal = evalConstExpr(binExpr.right);
            

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
        
        return null;
    }

    /**
     * 计算二元运算表达式的值
     */
    private Object computeBinaryOp(Object left, String op, Object right) {

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