package MiddleEnd.Optimization.Utils;

import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;

import java.util.*;

/**
 * 克隆助手，用于复制IR值和指令
 */
public class CloneHelper {
    private final Map<Value, Value> valueMap = new HashMap<>();
    private static int copyCounter = 0; // 添加静态计数器
    
    public void clear() {
        valueMap.clear();
    }
    
    public void addValueMapping(Value original, Value clone) {
        valueMap.put(original, clone);
    }
    
    public Value findValue(Value value) {
        if (value instanceof Constant) {
            return value; // 常量不需要映射
        }
        return valueMap.getOrDefault(value, value);
    }
    
    /**
     * 复制指令
     */
    public Instruction copyInstruction(Instruction inst) {
        if (inst instanceof AllocaInstruction allocaInst) {
            return copyAllocaInstruction(allocaInst);
        } else if (inst instanceof LoadInstruction loadInst) {
            return copyLoadInstruction(loadInst);
        } else if (inst instanceof StoreInstruction storeInst) {
            return copyStoreInstruction(storeInst);
        } else if (inst instanceof BinaryInstruction binaryInst) {
            return copyBinaryInstruction(binaryInst);
        } else if (inst instanceof CompareInstruction cmpInst) {
            return copyCompareInstruction(cmpInst);
        } else if (inst instanceof BranchInstruction brInst) {
            return copyBranchInstruction(brInst);
        } else if (inst instanceof ReturnInstruction retInst) {
            return copyReturnInstruction(retInst);
        } else if (inst instanceof CallInstruction callInst) {
            return copyCallInstruction(callInst);
        } else if (inst instanceof PhiInstruction phiInst) {
            return copyPhiInstruction(phiInst);
        } else if (inst instanceof GetElementPtrInstruction gepInst) {
            return copyGetElementPtrInstruction(gepInst);
        } else if (inst instanceof ConversionInstruction convInst) {
            return copyConversionInstruction(convInst);
        }
        
        // 未知指令类型，抛出异常
        throw new UnsupportedOperationException("Unsupported instruction type: " + inst.getClass().getSimpleName());
    }
    
    private AllocaInstruction copyAllocaInstruction(AllocaInstruction original) {
        AllocaInstruction copy = IRBuilder.createAlloca(original.getAllocatedType(), null);
        // hasArraySize方法暂时不支持
        // if (original.hasArraySize()) {
        //     copy.setArraySize(findValue(original.getArraySize()));
        // }
        return copy;
    }
    
    private LoadInstruction copyLoadInstruction(LoadInstruction original) {
        Value pointer = findValue(original.getPointer());
        return IRBuilder.createLoad(pointer, null);
    }
    
    private StoreInstruction copyStoreInstruction(StoreInstruction original) {
        Value value = findValue(original.getValue());
        Value pointer = findValue(original.getPointer());
        return IRBuilder.createStore(value, pointer, null);
    }
    
    private BinaryInstruction copyBinaryInstruction(BinaryInstruction original) {
        Value left = findValue(original.getLeft());
        Value right = findValue(original.getRight());
        return IRBuilder.createBinaryInst(original.getOpCode(), left, right, null);
    }
    
    private CompareInstruction copyCompareInstruction(CompareInstruction original) {
        Value left = findValue(original.getLeft());
        Value right = findValue(original.getRight());
        return IRBuilder.createICmp(original.getPredicate(), left, right, null);
    }
    
    private BranchInstruction copyBranchInstruction(BranchInstruction original) {
        if (original.isUnconditional()) {
            BasicBlock target = (BasicBlock) findValue(original.getTrueBlock());
            return new BranchInstruction(target);
        } else {
            Value condition = findValue(original.getCondition());
            BasicBlock trueBlock = (BasicBlock) findValue(original.getTrueBlock());
            BasicBlock falseBlock = (BasicBlock) findValue(original.getFalseBlock());
            return new BranchInstruction(condition, trueBlock, falseBlock);
        }
    }
    
    private ReturnInstruction copyReturnInstruction(ReturnInstruction original) {
        if (original.getReturnValue() != null) {
            Value returnValue = findValue(original.getReturnValue());
            return IRBuilder.createReturn(returnValue, null);
        } else {
            return IRBuilder.createReturn(null);
        }
    }
    
    private CallInstruction copyCallInstruction(CallInstruction original) {
        Function callee = original.getCallee();
        List<Value> args = new ArrayList<>();
        for (Value arg : original.getArguments()) {
            args.add(findValue(arg));
        }
        return IRBuilder.createCall(callee, args, null);
    }
    
    private PhiInstruction copyPhiInstruction(PhiInstruction original) {
        // 创建新的phi节点，但不预先添加操作数
        String uniqueName = original.getName() + "_copy_" + (copyCounter++);
        PhiInstruction phi = new PhiInstruction(original.getType(), uniqueName);
        
        // 注意：不在这里添加操作数，让后续的更新逻辑处理
        // 这样可以避免操作数和前驱块不匹配的问题
        
        return phi;
    }
    
    private GetElementPtrInstruction copyGetElementPtrInstruction(GetElementPtrInstruction original) {
        Value pointer = findValue(original.getPointer());
        List<Value> indices = original.getIndices();
        if (indices.size() == 1) {
            Value index = findValue(indices.get(0));
            return IRBuilder.createGetElementPtr(pointer, index, null);
        } else {
            List<Value> newIndices = new ArrayList<>();
            for (Value index : indices) {
                newIndices.add(findValue(index));
            }
            return IRBuilder.createGetElementPtr(pointer, newIndices, null);
        }
    }
    
    private ConversionInstruction copyConversionInstruction(ConversionInstruction original) {
        Value src = findValue(original.getSource());
        // 使用IRBuilder创建转换指令，保持相同的目标类型和转换操作码
        return IRBuilder.createConversion(src, original.getType(), original.getConversionType(), null);
    }
    
    /**
     * 复制基本块到另一个基本块
     */
    public void copyBlockToBlock(BasicBlock source, BasicBlock target) {
        for (Instruction inst : source.getInstructions()) {
            Instruction copy = copyInstruction(inst);
            target.addInstruction(copy);
            addValueMapping(inst, copy);
        }
    }
    
    /**
     * 复制基本块
     */
    public BasicBlock copyBasicBlock(BasicBlock original, Function targetFunction) {
        BasicBlock copy = IRBuilder.createBasicBlock(original.getName() + "_copy", targetFunction);
        copyBlockToBlock(original, copy);
        addValueMapping(original, copy);
        return copy;
    }
    
    /**
     * 获取值映射
     */
    public Map<Value, Value> getValueMap() {
        return new HashMap<>(valueMap);
    }
    
    /**
     * 获取值映射的直接引用（用于内部查找）
     */
    public Map<Value, Value> getValueMapDirect() {
        return valueMap;
    }
} 