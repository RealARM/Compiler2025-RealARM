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

        @Override
        public String toString() {
            return SyntaxTree.toTreeString(this);
        }
    }

    /** 顶层定义：变量声明或函数定义 */
    public interface TopLevelDef extends Node {
    }

    /* -------------------- 声明相关 -------------------- */

    /** 变量声明（含常量）。在语句块中也可作为Stmt出现 */
    public static class VarDecl implements TopLevelDef, Stmt {
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

    // ==================== Pretty Print Support ====================
    /**
     * 将该语法树节点转换为易读的字符串表示，默认从缩进 0 开始。
     */
    public static String toTreeString(Node node) {
        return toTreeString(node, 0);
    }

    /**
     * 递归地将节点转换为字符串。
     * @param node   语法树节点
     * @param indent 当前缩进
     * @return       可读字符串
     */
    private static String toTreeString(Node node, int indent) {
        if (node == null) {
            return "null";
        }
        String ind = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        // Compilation unit
        if (node instanceof CompilationUnit cu) {
            sb.append("AST {\n");
            for (TopLevelDef def : cu.defs) {
                sb.append(toTreeString(def, indent + 2)).append("\n");
            }
            sb.append("}");
            return sb.toString();
        }
        // Variable declaration
        if (node instanceof VarDecl decl) {
            sb.append(ind).append("Decl {\n");
            sb.append(ind).append("  constant: ").append(decl.isConst).append("\n");
            sb.append(ind).append("  bType: ").append(decl.baseType).append("\n");
            sb.append(ind).append("  defs: [\n");
            for (VarDef var : decl.variables) {
                sb.append(ind).append("    { ident: ").append(var.ident);
                if (var.dims != null && !var.dims.isEmpty()) {
                    sb.append(", dims: ").append(var.dims);
                }
                if (var.init != null) {
                    sb.append(", init: ").append(exprToString(var.init));
                }
                sb.append(" }\n");
            }
            sb.append(ind).append("  ]\n");
            sb.append(ind).append("}");
            return sb.toString();
        }
        // Function definition
        if (node instanceof FuncDef func) {
            sb.append(ind).append("FuncDef {\n");
            sb.append(ind).append("  retType: ").append(func.retType).append("\n");
            sb.append(ind).append("  name: ").append(func.name).append("\n");
            sb.append(ind).append("  params: [");
            if (func.params != null) {
                for (int i = 0; i < func.params.size(); i++) {
                    Param p = func.params.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(p.name).append(p.isArray ? "[]" : "");
                }
            }
            sb.append("]\n");
            sb.append(ind).append("  body: ").append(toTreeString(func.body, indent + 2)).append("\n");
            sb.append(ind).append("}");
            return sb.toString();
        }
        // Block
        if (node instanceof Block block) {
            sb.append("Block {\n");
            for (Stmt stmt : block.stmts) {
                sb.append(toTreeString(stmt, indent + 2)).append("\n");
            }
            sb.append(" ".repeat(indent)).append("}");
            return sb.toString();
        }
        // Statements
        if (node instanceof ExprStmt eStmt) {
            sb.append(ind).append("ExprStmt { ").append(exprToString(eStmt.expr)).append(" }");
            return sb.toString();
        }
        if (node instanceof AssignStmt aStmt) {
            sb.append(ind).append("Assign { ").append(aStmt.target.name).append(" = ")
                    .append(exprToString(aStmt.value)).append(" }");
            return sb.toString();
        }
        if (node instanceof ReturnStmt ret) {
            sb.append(ind).append("Return { ")
                    .append(ret.value != null ? exprToString(ret.value) : "void").append(" }");
            return sb.toString();
        }
        if (node instanceof BreakStmt) {
            sb.append(ind).append("Break");
            return sb.toString();
        }
        if (node instanceof ContinueStmt) {
            sb.append(ind).append("Continue");
            return sb.toString();
        }
        if (node instanceof IfStmt ifs) {
            sb.append(ind).append("IfStmt { cond: ").append(exprToString(ifs.cond)).append(" then: ")
                    .append(toTreeString(ifs.thenBranch, indent + 2));
            if (ifs.elseBranch != null) {
                sb.append(" else: ").append(toTreeString(ifs.elseBranch, indent + 2));
            }
            sb.append(" }");
            return sb.toString();
        }
        if (node instanceof WhileStmt wh) {
            sb.append(ind).append("WhileStmt { cond: ").append(exprToString(wh.cond))
                    .append(" body: ").append(toTreeString(wh.body, indent + 2)).append(" }");
            return sb.toString();
        }
        // Fallback to default toString
        return ind + node.getClass().getSimpleName();
    }

    // -------------------- Expression helper --------------------
    private static String exprToString(Expr expr) {
        return exprToString(expr, 0);
    }

    private static String exprToString(Expr expr, int indent) {
        if (expr == null) return "null";
        String ind = " ".repeat(indent);
        StringBuilder sb = new StringBuilder();
        if (expr instanceof LiteralExpr lit) {
            sb.append(lit.value);
        } else if (expr instanceof VarExpr var) {
            sb.append(var.name);
        } else if (expr instanceof UnaryExpr un) {
            sb.append(un.op).append(exprToString(un.expr, indent));
        } else if (expr instanceof BinaryExpr bin) {
            sb.append(exprToString(bin.left, indent)).append(' ').append(bin.op).append(' ')
                    .append(exprToString(bin.right, indent));
        } else if (expr instanceof CallExpr call) {
            sb.append(call.funcName).append("(");
            for (int i = 0; i < call.args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(exprToString(call.args.get(i), indent));
            }
            sb.append(")");
        } else {
            sb.append(expr.getClass().getSimpleName());
        }
        return ind + sb.toString();
    }

    // 为主要节点覆写 toString，方便打印
    @Override
    public String toString() {
        // 该方法在 SyntaxTree 本身被调用时不应触发，实际输出依赖 CompilationUnit
        return "";
    }
} 