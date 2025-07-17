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
} 