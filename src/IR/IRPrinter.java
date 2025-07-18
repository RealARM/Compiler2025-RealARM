package IR;

import IR.Type.*;
import IR.Value.*;
import IR.Value.Instructions.*;
import java.util.Map;

import java.io.PrintStream;
import java.util.List;

/**
 * IR打印器，用于将IR以文本形式输出
 */
public class IRPrinter {
    private PrintStream out;
    
    public IRPrinter(PrintStream out) {
        this.out = out;
    }
    
    /**
     * 打印整个IR模块
     */
    public void printModule(Module module) {
        // 打印全局变量
        for (GlobalVariable gv : module.globalVars()) {
            printGlobalVariable(gv);
            out.println();
        }
        
        // 打印函数声明
        for (Function func : module.libFunctions()) {
            printFunctionDeclaration(func);
            out.println();
        }
        
        // 打印函数定义
        for (Function func : module.functions()) {
            printFunction(func);
            out.println();
        }
    }
    
    /**
     * 打印全局变量
     */
    private void printGlobalVariable(GlobalVariable gv) {
        out.print(gv.getName());
        out.print(" = ");
        if (gv.isConstant()) {
            out.print("constant ");
        } else {
            out.print("global ");
        }
        
        // 打印类型和初始值
        if (gv.hasInitializer()) {
            Value initializer = gv.getInitializer();
            if (initializer instanceof Constant) {
                out.print(((PointerType)gv.getType()).getElementType());
                out.print(" ");
                printConstant((Constant)initializer);
            } else if (gv.isZeroInitialized()) {
                out.print(((PointerType)gv.getType()).getElementType());
                out.print(" zeroinitializer");
            }
        } else {
            out.print(((PointerType)gv.getType()).getElementType());
            out.print(" undef");
        }
    }
    
    /**
     * 打印常量值
     */
    private void printConstant(Constant constant) {
        if (constant instanceof ConstantInt) {
            out.print(((ConstantInt) constant).getValue());
        } else if (constant instanceof ConstantFloat) {
            out.print(((ConstantFloat) constant).getValue());
        }
    }
    
    /**
     * 打印函数声明
     */
    private void printFunctionDeclaration(Function func) {
        out.print("declare ");
        out.print(func.getReturnType());
        out.print(" ");
        out.print(func.getName());
        out.print("(");
        
        List<Argument> args = func.getArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.print(", ");
            }
            out.print(args.get(i).getType());
        }
        
        out.print(")");
    }
    
    /**
     * 打印函数定义
     */
    private void printFunction(Function func) {
        out.print("define ");
        out.print(func.getReturnType());
        out.print(" ");
        out.print(func.getName());
        out.print("(");
        
        List<Argument> args = func.getArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.print(", ");
            }
            Argument arg = args.get(i);
            out.print(arg.getType());
            out.print(" ");
            out.print(arg.getName());
        }
        
        out.println(") {");
        
        // 打印基本块
        for (BasicBlock bb : func.getBasicBlocks()) {
            printBasicBlock(bb);
        }
        
        out.println("}");
    }
    
    /**
     * 打印基本块
     */
    private void printBasicBlock(BasicBlock bb) {
        out.print(bb.getName());
        out.println(":");
        
        // 检查基本块是否为空
        if (bb.getInstructions().isEmpty()) {
            // 如果基本块为空，添加一个默认的无条件跳转或返回指令
            out.print("  ");
            
            // 尝试获取下一个基本块
            BasicBlock nextBlock = getNextBasicBlock(bb);
            
            if (nextBlock != null) {
                // 添加一个无条件跳转到下一个基本块
                out.println("br label %" + nextBlock.getName());
            } else {
                // 如果没有下一个基本块，添加一个默认返回指令
                Function parentFunction = bb.getParentFunction();
                Type returnType = parentFunction.getReturnType();
                
                if (returnType instanceof VoidType) {
                    out.println("ret void");
                } else if (returnType instanceof IntegerType) {
                    out.println("ret i32 0");
                } else if (returnType instanceof FloatType) {
                    out.println("ret float 0.0");
                } else {
                    out.println("ret i32 0");  // 默认返回整数0
                }
            }
            return;
        }
        
        // 打印指令
        for (Instruction inst : bb.getInstructions()) {
            out.print("  ");
            printInstruction(inst);
            out.println();
        }
    }
    
    /**
     * 获取基本块的下一个基本块
     */
    private BasicBlock getNextBasicBlock(BasicBlock bb) {
        // 首先尝试从后继中获取
        List<BasicBlock> successors = bb.getSuccessors();
        if (!successors.isEmpty()) {
            return successors.get(0);
        }
        
        // 如果没有后继，尝试从函数的基本块列表中获取下一个
        Function function = bb.getParentFunction();
        List<BasicBlock> blocks = function.getBasicBlocks();
        
        for (int i = 0; i < blocks.size() - 1; i++) {
            if (blocks.get(i) == bb) {
                return blocks.get(i + 1);
            }
        }
        
        return null;
    }
    
    /**
     * 打印变量名，为局部变量添加%前缀
     */
    private String printValueName(Value value) {
        String name = value.getName();
        
        // 全局变量和函数已经有@前缀，不需要添加%
        if (name.startsWith("@")) {
            return name;
        }
        
        // 数字常量不需要前缀
        if (value instanceof Constant) {
            return name;
        }
        
        // 局部变量添加%前缀
        if (!name.startsWith("%")) {
            return "%" + name;
        }
        
        return name;
    }

    /**
     * 打印指令
     */
    private void printInstruction(Instruction inst) {
        // 如果指令有结果值，打印赋值
        if (!(inst instanceof StoreInstruction || inst instanceof BranchInstruction || inst instanceof ReturnInstruction) &&
            !(inst instanceof CallInstruction && ((CallInstruction) inst).isVoidCall())) {
            out.print(printValueName(inst));
            out.print(" = ");
        }
        
        // 根据指令类型打印
        if (inst instanceof BinaryInstruction) {
            printBinaryInstruction((BinaryInstruction) inst);
        } else if (inst instanceof LoadInstruction) {
            printLoadInstruction((LoadInstruction) inst);
        } else if (inst instanceof StoreInstruction) {
            printStoreInstruction((StoreInstruction) inst);
        } else if (inst instanceof AllocaInstruction) {
            printAllocaInstruction((AllocaInstruction) inst);
        } else if (inst instanceof GetElementPtrInstruction) {
            printGetElementPtrInstruction((GetElementPtrInstruction) inst);
        } else if (inst instanceof CallInstruction) {
            printCallInstruction((CallInstruction) inst);
        } else if (inst instanceof ReturnInstruction) {
            printReturnInstruction((ReturnInstruction) inst);
        } else if (inst instanceof BranchInstruction) {
            printBranchInstruction((BranchInstruction) inst);
        } else if (inst instanceof PhiInstruction) {
            printPhiInstruction((PhiInstruction) inst);
        } else if (inst instanceof ConversionInstruction) {
            printConversionInstruction((ConversionInstruction) inst);
        } else if (inst instanceof CompareInstruction) {
            printCompareInstruction((CompareInstruction) inst);
        } else {
            out.print("unknown instruction");
        }
    }
    
    /**
     * 打印二元运算指令
     */
    private void printBinaryInstruction(BinaryInstruction inst) {
        out.print(inst.getOpcodeName().toLowerCase());
        out.print(" ");
        out.print(inst.getOperand(0).getType());
        out.print(" ");
        out.print(printValueName(inst.getOperand(0)));
        out.print(", ");
        out.print(printValueName(inst.getOperand(1)));
    }
    
    /**
     * 打印加载指令
     */
    private void printLoadInstruction(LoadInstruction inst) {
        out.print("load ");
        Value pointer = inst.getPointer();
        Type pointedType = ((PointerType) pointer.getType()).getElementType();
        out.print(pointedType);
        out.print(", ");
        out.print(pointer.getType());
        out.print(" ");
        out.print(printValueName(pointer));
    }
    
    /**
     * 打印存储指令
     */
    private void printStoreInstruction(StoreInstruction inst) {
        out.print("store ");
        Value value = inst.getValue();
        Value pointer = inst.getPointer();
        out.print(value.getType());
        out.print(" ");
        out.print(printValueName(value));
        out.print(", ");
        out.print(pointer.getType());
        out.print(" ");
        out.print(printValueName(pointer));
    }
    
    /**
     * 打印分配指令
     */
    private void printAllocaInstruction(AllocaInstruction inst) {
        out.print("alloca ");
        Type allocatedType = ((PointerType) inst.getType()).getElementType();
        out.print(allocatedType);
        
        if (inst instanceof AllocaInstruction && ((AllocaInstruction)inst).getArraySize() > 0) {
            out.print(", i32 ");
            out.print(((AllocaInstruction)inst).getArraySize());
        }
    }
    
    /**
     * 打印获取元素指针指令
     */
    private void printGetElementPtrInstruction(GetElementPtrInstruction inst) {
        out.print("getelementptr ");
        Value pointer = inst.getPointer();
        Type pointedType = ((PointerType) pointer.getType()).getElementType();
        out.print(pointedType);
        out.print(", ");
        out.print(pointer.getType());
        out.print(" ");
        out.print(printValueName(pointer));
        
        // 打印索引，这里简化处理，只考虑单个索引的情况
        if (inst.getOperandCount() > 1) {
            Value index = inst.getOperand(1);
            out.print(", ");
            out.print(index.getType());
            out.print(" ");
            out.print(printValueName(index));
        }
    }
    
    /**
     * 打印函数调用指令
     */
    private void printCallInstruction(CallInstruction inst) {
        out.print("call ");
        Function callee = (Function)inst.getOperand(0);
        out.print(callee.getReturnType());
        out.print(" ");
        out.print(callee.getName());
        out.print("(");
        
        List<Value> args = inst.getOperands().subList(1, inst.getOperandCount());
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.print(", ");
            }
            Value arg = args.get(i);
            out.print(arg.getType());
            out.print(" ");
            out.print(printValueName(arg));
        }
        
        out.print(")");
    }
    
    /**
     * 打印返回指令
     */
    private void printReturnInstruction(ReturnInstruction inst) {
        out.print("ret ");
        if (inst.getOperandCount() > 0) {
            Value value = inst.getOperand(0);
            out.print(value.getType());
            out.print(" ");
            out.print(printValueName(value));
        } else {
            out.print("void");
        }
    }
    
    /**
     * 打印分支指令
     */
    private void printBranchInstruction(BranchInstruction inst) {
        out.print("br ");
        if (inst.getOperandCount() > 1) {
            Value condition = inst.getOperand(0);
            out.print(condition.getType());
            out.print(" ");
            out.print(printValueName(condition));
            out.print(", label %");
            out.print(((BasicBlock)inst.getOperand(1)).getName());
            out.print(", label %");
            out.print(((BasicBlock)inst.getOperand(2)).getName());
        } else {
            out.print("label %");
            out.print(((BasicBlock)inst.getOperand(0)).getName());
        }
    }
    
    /**
     * 打印Phi指令
     */
    private void printPhiInstruction(PhiInstruction inst) {
        out.print("phi ");
        out.print(inst.getType());
        out.print(" ");
        
        // PhiInstruction的操作数是成对的，一个值对应一个基本块
        Map<BasicBlock, Value> incomingValues = ((PhiInstruction)inst).getIncomingValues();
        int i = 0;
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            if (i > 0) {
                out.print(", ");
            }
            out.print("[ ");
            out.print(printValueName(entry.getValue()));
            out.print(", %");
            out.print(entry.getKey().getName());
            out.print(" ]");
            i++;
        }
    }
    
    /**
     * 打印转换指令
     */
    private void printConversionInstruction(ConversionInstruction inst) {
        out.print(inst.getOpcodeName().toLowerCase());
        out.print(" ");
        out.print(inst.getSource().getType());
        out.print(" ");
        out.print(printValueName(inst.getSource()));
        out.print(" to ");
        out.print(inst.getType());
    }
    
    /**
     * 打印比较指令
     */
    private void printCompareInstruction(CompareInstruction inst) {
        out.print(inst.getOpcodeName().toLowerCase());
        out.print(" ");
        out.print(inst.getPredicate().getName().toLowerCase());
        out.print(" ");
        out.print(inst.getLeft().getType());
        out.print(" ");
        out.print(printValueName(inst.getLeft()));
        out.print(", ");
        out.print(printValueName(inst.getRight()));
    }
} 