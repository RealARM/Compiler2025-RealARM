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
        // 读取 '('
        tokens.expect(SysYTokenType.LEFT_PAREN);
        // 暂时忽略形参，直到遇到 ')'
        while (!tokens.check(SysYTokenType.RIGHT_PAREN)) {
            tokens.next();
        }
        tokens.expect(SysYTokenType.RIGHT_PAREN);

        // 读取函数体的大括号，对内部暂不解析，只找到匹配的 '}'
        tokens.expect(SysYTokenType.LEFT_BRACE);
        int braceDepth = 1;
        while (braceDepth > 0) {
            SysYToken tok = tokens.next();
            if (tok == null) {
                throw new SyntaxException("Unexpected EOF inside function body of " + name);
            }
            if (tok.getType() == SysYTokenType.LEFT_BRACE) braceDepth++;
            if (tok.getType() == SysYTokenType.RIGHT_BRACE) braceDepth--;
        }

        // 目前不解析函数体内容，先存为空 block
        return new FuncDef(retType, name, new ArrayList<>(), new Block(new ArrayList<>()));
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
} 