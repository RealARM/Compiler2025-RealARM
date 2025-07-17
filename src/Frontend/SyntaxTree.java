package Frontend;

import java.util.List;

/**
 * SysY 语言的抽象语法树（AST）节点定义（精简版）。
 * 为了保持与示例代码的差异，这里采用不同的命名风格与结构组织。
 */
public class SyntaxTree {

    /* -------------------- 基础接口 -------------------- */

    /** 所有 AST 节点的公共接口 */
    public interface Node {
    }

    /** 编译单元（完整程序） */
    public static class CompilationUnit implements Node {
        private final List<TopLevelDef> defs;

        public CompilationUnit(List<TopLevelDef> defs) {
            this.defs = defs;
        }

        public List<TopLevelDef> getDefs() {
            return defs;
        }
    }

    /** 顶层定义：变量声明或函数定义 */
    public interface TopLevelDef extends Node {
    }

    /* -------------------- 声明相关 -------------------- */

    /** 变量声明（含常量） */
    public static class VarDecl implements TopLevelDef {
        public final boolean isConst;
        public final String baseType; // int / float
        public final List<VarDef> variables;

        public VarDecl(boolean isConst, String baseType, List<VarDef> variables) {
            this.isConst = isConst;
            this.baseType = baseType;
            this.variables = variables;
        }
    }

    /** 单个变量定义（可能带初始化和数组维度） */
    public static class VarDef {
        public final String ident;
        public final List<Integer> dims; // 维度长度常量表达式，这里先用整数占位
        public final Expr init; // 初始化表达式，暂可为空

        public VarDef(String ident, List<Integer> dims, Expr init) {
            this.ident = ident;
            this.dims = dims;
            this.init = init;
        }
    }

    /* -------------------- 函数相关 -------------------- */

    public static class FuncDef implements TopLevelDef {
        public final String retType; // void/int/float
        public final String name;
        public final List<Param> params;
        public final Block body;

        public FuncDef(String retType, String name, List<Param> params, Block body) {
            this.retType = retType;
            this.name = name;
            this.params = params;
            this.body = body;
        }
    }

    /** 函数参数 */
    public static class Param {
        public final String type; // int/float
        public final String name;
        public final boolean isArray;

        public Param(String type, String name, boolean isArray) {
            this.type = type;
            this.name = name;
            this.isArray = isArray;
        }
    }

    /* -------------------- 语句与表达式（简化） -------------------- */

    public interface Stmt extends Node {
    }

    public static class Block implements Stmt {
        public final List<Stmt> stmts;

        public Block(List<Stmt> stmts) {
            this.stmts = stmts;
        }
    }

    public interface Expr extends Node {
    }

    /* -------------------- 新增表达式节点 -------------------- */
    public static class LiteralExpr implements Expr {
        public final Object value; // Integer, Float, String
        public LiteralExpr(Object value) {
            this.value = value;
        }
    }

    public static class VarExpr implements Expr {
        public final String name;
        public VarExpr(String name) {
            this.name = name;
        }
    }

    public static class BinaryExpr implements Expr {
        public final Expr left;
        public final String op;
        public final Expr right;
        public BinaryExpr(Expr left, String op, Expr right) {
            this.left = left;
            this.op = op;
            this.right = right;
        }
    }

    public static class UnaryExpr implements Expr {
        public final String op;
        public final Expr expr;
        public UnaryExpr(String op, Expr expr) {
            this.op = op;
            this.expr = expr;
        }
    }

    public static class CallExpr implements Expr {
        public final String funcName;
        public final List<Expr> args;
        public CallExpr(String funcName, List<Expr> args) {
            this.funcName = funcName;
            this.args = args;
        }
    }

    /* -------------------- 新增语句节点 -------------------- */
    public static class ExprStmt implements Stmt {
        public final Expr expr;
        public ExprStmt(Expr expr) { this.expr = expr; }
    }

    public static class AssignStmt implements Stmt {
        public final VarExpr target;
        public final Expr value;
        public AssignStmt(VarExpr target, Expr value) { this.target = target; this.value = value; }
    }

    public static class ReturnStmt implements Stmt {
        public final Expr value; // 可能为空
        public ReturnStmt(Expr value) { this.value = value; }
    }

    public static class BreakStmt implements Stmt {}
    public static class ContinueStmt implements Stmt {}

    public static class IfStmt implements Stmt {
        public final Expr cond;
        public final Stmt thenBranch;
        public final Stmt elseBranch; // 可能为空
        public IfStmt(Expr cond, Stmt thenBranch, Stmt elseBranch) {
            this.cond = cond;
            this.thenBranch = thenBranch;
            this.elseBranch = elseBranch;
        }
    }

    public static class WhileStmt implements Stmt {
        public final Expr cond;
        public final Stmt body;
        public WhileStmt(Expr cond, Stmt body) { this.cond = cond; this.body = body; }
    }
} 