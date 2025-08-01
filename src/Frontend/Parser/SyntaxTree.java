package Frontend.Parser;

import java.util.List;
import java.util.ArrayList;

public class SyntaxTree {

    public interface Node {
    }
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

    public interface TopLevelDef extends Node {
    }

    // 变量声明（含常量）
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

    public static class VarDef {
        public final String ident;
        public final List<Integer> dims; // 数组维度
        public final Expr init; // 初始化表达式

        public VarDef(String ident, List<Integer> dims, Expr init) {
            this.ident = ident;
            this.dims = dims;
            this.init = init;
        }
    }

    public static class FuncDef implements TopLevelDef {
        public final String retType;
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

    public static class Param {
        public final String type;
        public final String name;
        public final boolean isArray;
        public final List<Expr> dimensions; // 数组维度

        public Param(String type, String name, boolean isArray) {
            this(type, name, isArray, new ArrayList<>());
        }
        
        public Param(String type, String name, boolean isArray, List<Expr> dimensions) {
            this.type = type;
            this.name = name;
            this.isArray = isArray;
            this.dimensions = dimensions;
        }
    }

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
    public static class LiteralExpr implements Expr {
        public final Object value;
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

    // 数组访问表达式
    public static class ArrayAccessExpr implements Expr {
        public final String arrayName;
        public final List<Expr> indices;
        
        public ArrayAccessExpr(String arrayName, List<Expr> indices) {
            this.arrayName = arrayName;
            this.indices = indices;
        }
    }

    // 数组初始化表达式
    public static class ArrayInitExpr implements Expr {
        public final List<Expr> elements;
        
        public ArrayInitExpr(List<Expr> elements) {
            this.elements = elements;
        }
    }


    public static class ExprStmt implements Stmt {
        public final Expr expr;
        public ExprStmt(Expr expr) { this.expr = expr; }
    }

    public static class AssignStmt implements Stmt {
        public final Expr target;
        public final Expr value;
        public AssignStmt(Expr target, Expr value) { 
            this.target = target; 
            this.value = value; 
        }
    }

    public static class ReturnStmt implements Stmt {
        public final Expr value;
        public ReturnStmt(Expr value) { this.value = value; }
    }

    public static class BreakStmt implements Stmt {}
    public static class ContinueStmt implements Stmt {}

    public static class IfStmt implements Stmt {
        public final Expr cond;
        public final Stmt thenBranch;
        public final Stmt elseBranch;
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


    public static String toTreeString(Node node) {
        return toTreeString(node, 0);
    }


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
            sb.append(ind).append("Assign { ").append(exprToString(aStmt.target)).append(" = ")
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
        } else if (expr instanceof ArrayAccessExpr arr) {
            sb.append(arr.arrayName).append("[");
            for (int i = 0; i < arr.indices.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(exprToString(arr.indices.get(i), indent));
            }
            sb.append("]");
        } else if (expr instanceof ArrayInitExpr arr) {
            sb.append("{ ");
            for (int i = 0; i < arr.elements.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(exprToString(arr.elements.get(i), indent));
            }
            sb.append(" }");
        } else {
            sb.append(expr.getClass().getSimpleName());
        }
        return ind + sb.toString();
    }

    @Override
    public String toString() {
        return "";
    }
} 