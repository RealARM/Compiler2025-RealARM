package MiddleEnd.IR;

import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import java.util.Map;

import java.io.PrintStream;
import java.util.List;

public class IRPrinter {
    private PrintStream out;
    
    public IRPrinter(PrintStream out) {
        this.out = out;
    }
    
    public void printModule(Module module) {
        for (GlobalVariable gv : module.globalVars()) {
            printGlobalVariable(gv);
            out.println();
        }
        
        for (Function func : module.libFunctions()) {
            printFunctionDeclaration(func);
            out.println();
        }
        
        for (Function func : module.functions()) {
            printFunction(func);
            out.println();
        }
    }
    
    private void printGlobalVariable(GlobalVariable gv) {
        out.print(gv.getName());
        out.print(" = ");
        if (gv.isConstant()) {
            out.print("constant ");
        } else {
            out.print("global ");
        }
        
        Type elementType = ((PointerType)gv.getType()).getElementType();
        
        if (gv.isArray()) {
            out.print("[" + gv.getArraySize() + " x " + elementType + "]");
            
            if (gv.isZeroInitialized()) {
                out.print(" zeroinitializer");
            } else if (gv.getArrayValues() != null && !gv.getArrayValues().isEmpty()) {
                out.print(" [");
                List<Value> values = gv.getArrayValues();
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        out.print(", ");
                    }
                    out.print(elementType + " ");
                    printConstant((Constant)values.get(i));
                }
                out.print("]");
            } else {
                out.print(" undef");
            }
        } else if (gv.hasInitializer()) {
            Value initializer = gv.getInitializer();
            if (initializer instanceof Constant) {
                out.print(elementType);
                out.print(" ");
                printConstant((Constant)initializer);
            } else if (gv.isZeroInitialized()) {
                out.print(elementType);
                out.print(" zeroinitializer");
            }
        } else {
            out.print(elementType);
            out.print(" undef");
        }
    }
    
    private void printConstant(Constant constant) {
        if (constant instanceof ConstantInt) {
            out.print(((ConstantInt) constant).getValue());
        } else if (constant instanceof ConstantFloat) {
            double value = ((ConstantFloat) constant).getValue();
            out.print("0x" + Long.toHexString(Double.doubleToLongBits(value)));
        }
    }
    
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
        
        for (BasicBlock bb : func.getBasicBlocks()) {
            printBasicBlock(bb);
        }
        
        out.println("}");
    }
    
    private void printBasicBlock(BasicBlock bb) {
        out.print(bb.getName());
        out.println(":");
        
        if (bb.getInstructions().isEmpty()) {
            out.print("  ");
            
            BasicBlock nextBlock = getNextBasicBlock(bb);
            
            if (nextBlock != null) {
                out.println("br label %" + nextBlock.getName());
            } else {
                Function parentFunction = bb.getParentFunction();
                Type returnType = parentFunction.getReturnType();
                
                if (returnType instanceof VoidType) {
                    out.println("ret void");
                } else if (returnType instanceof IntegerType) {
                    out.println("ret i32 0");
                } else if (returnType instanceof FloatType) {
                    out.println("ret float 0x0000000000000000");
                } else {
                    out.println("ret i32 0");
                }
            }
            return;
        }
        
        for (Instruction inst : bb.getInstructions()) {
            out.print("  ");
            printInstruction(inst);
            out.println();
        }
    }
    
    private BasicBlock getNextBasicBlock(BasicBlock bb) {
        List<BasicBlock> successors = bb.getSuccessors();
        if (!successors.isEmpty()) {
            return successors.get(0);
        }
        
        Function function = bb.getParentFunction();
        List<BasicBlock> blocks = function.getBasicBlocks();
        
        for (int i = 0; i < blocks.size() - 1; i++) {
            if (blocks.get(i) == bb) {
                return blocks.get(i + 1);
            }
        }
        
        return null;
    }
    
    private String printValueName(Value value) {
        String name = value.getName();
        
        if (name.startsWith("@")) {
            return name;
        }

        if (value instanceof ConstantFloat) {
            return "0x" + Long.toHexString(Double.doubleToLongBits((float)((ConstantFloat) value).getValue()));
        }
        
        if (value instanceof Constant) {
            return name;
        }
        
        if (!name.startsWith("%")) {
            return "%" + name;
        }
        
        return name;
    }

    private void printInstruction(Instruction inst) {
        if (!(inst instanceof StoreInstruction || inst instanceof BranchInstruction || inst instanceof ReturnInstruction) &&
            !(inst instanceof CallInstruction && ((CallInstruction) inst).isVoidCall())) {
            out.print(printValueName(inst));
            out.print(" = ");
        }
        
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
        } else if (inst instanceof MoveInstruction) {
            printMoveInstruction((MoveInstruction) inst);
        } else {
            out.print("unknown instruction");
        }
    }
    
    private void printBinaryInstruction(BinaryInstruction inst) {
        out.print(inst.getOpcodeName().toLowerCase());
        out.print(" ");
        out.print(inst.getType());
        out.print(" ");
        
        if (inst.getLeft() instanceof ConstantFloat) {
            out.print(inst.getLeft());
        } else {
            out.print(printValueName(inst.getLeft()));
        }
        
        out.print(", ");
        
        if (inst.getRight() instanceof ConstantFloat) {
            out.print(inst.getRight());
        } else {
            out.print(printValueName(inst.getRight()));
        }
    }

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
    
    private void printStoreInstruction(StoreInstruction inst) {
        out.print(inst.getOpcodeName().toLowerCase());
        out.print(" ");
        out.print(inst.getValue().getType());
        out.print(" ");
        if (inst.getValue() instanceof ConstantFloat) {
            double value = ((ConstantFloat) inst.getValue()).getValue();
            out.print("0x" + Long.toHexString(Double.doubleToLongBits(value)));
        } else {
            out.print(printValueName(inst.getValue()));
        }
        out.print(", ");
        out.print(inst.getPointer().getType());
        out.print(" ");
        out.print(printValueName(inst.getPointer()));
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
    
    private void printGetElementPtrInstruction(GetElementPtrInstruction inst) {
        out.print("getelementptr ");
        Value pointer = inst.getPointer();
        Type pointedType = ((PointerType) pointer.getType()).getElementType();
        out.print(pointedType);
        out.print(", ");
        out.print(pointer.getType());
        out.print(" ");
        out.print(printValueName(pointer));
        
        if (inst.getOperandCount() > 1) {
            Value index = inst.getOperand(1);
            out.print(", ");
            out.print(index.getType());
            out.print(" ");
            out.print(printValueName(index));
        }
    }
    
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
    
    private void printPhiInstruction(PhiInstruction inst) {
        out.print("phi ");
        out.print(inst.getType());
        out.print(" ");
        
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
    
    private void printConversionInstruction(ConversionInstruction inst) {
        out.print(inst.getOpcodeName().toLowerCase());
        out.print(" ");
        out.print(inst.getSource().getType());
        out.print(" ");
        out.print(printValueName(inst.getSource()));
        out.print(" to ");
        out.print(inst.getType());
    }
    
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

    private void printMoveInstruction(MoveInstruction inst) {
        out.print("mov ");
        out.print(inst.getType());
        out.print(" ");
        out.print(printValueName(inst.getSource()));
    }
} 