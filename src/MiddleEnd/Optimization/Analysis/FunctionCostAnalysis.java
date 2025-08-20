package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Value.BasicBlock;
import MiddleEnd.IR.Value.Function;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.HashMap;
import java.util.Map;

public class FunctionCostAnalysis implements Optimizer.Analyzer {
    
    private static final int ARITHMETIC_COST = 1;
    private static final int MEMORY_COST = 5;
    private static final int MULTIPLY_COST = 10;
    private static final int CALL_COST = 20;
    private static final int BRANCH_COST = 2;
    private static final int CONVERSION_COST = 3;
    
    private Map<Function, Integer> functionCosts = new HashMap<>();
    
    @Override
    public String getName() {
        return "FunctionCostAnalysis";
    }
    
    @Override
    public void run(Module module) {
        functionCosts.clear();
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            int cost = calculateFunctionCost(function);
            functionCosts.put(function, cost);
        }
    }
    
    @Override
    public Object getResult() {
        return functionCosts;
    }
    
    public int getFunctionCost(Function function) {
        return functionCosts.getOrDefault(function, 0);
    }
    
    private int calculateFunctionCost(Function function) {
        int totalCost = 0;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                totalCost += getInstructionCost(inst);
            }
        }
        
        return totalCost;
    }
    
    private int getInstructionCost(Instruction inst) {
        if (inst instanceof BinaryInstruction) {
            BinaryInstruction binary = (BinaryInstruction) inst;
            String opcode = binary.getOpcodeName();
            
            if (opcode.equals("mul") || opcode.equals("sdiv") || 
                opcode.equals("udiv") || opcode.equals("srem") || 
                opcode.equals("urem") || opcode.equals("fmul") || 
                opcode.equals("fdiv") || opcode.equals("frem")) {
                return MULTIPLY_COST;
            }
            
            return ARITHMETIC_COST;
        }
        
        if (inst instanceof LoadInstruction || inst instanceof StoreInstruction) {
            return MEMORY_COST;
        }
        
        if (inst instanceof CallInstruction) {
            return CALL_COST;
        }
        
        if (inst instanceof BranchInstruction) {
            return BRANCH_COST;
        }
        
        if (inst instanceof ConversionInstruction) {
            return CONVERSION_COST;
        }
        
        if (inst instanceof CompareInstruction) {
            return ARITHMETIC_COST;
        }
        
        if (inst instanceof GetElementPtrInstruction) {
            return ARITHMETIC_COST;
        }
        
        if (inst instanceof AllocaInstruction) {
            return MEMORY_COST;
        }
        
        if (inst instanceof PhiInstruction) {
            return ARITHMETIC_COST;
        }
        
        return ARITHMETIC_COST;
    }
    
    public static class CostConfig {
        public static int arithmeticCost = ARITHMETIC_COST;
        public static int memoryCost = MEMORY_COST;
        public static int multiplyCost = MULTIPLY_COST;
        public static int callCost = CALL_COST;
        public static int branchCost = BRANCH_COST;
        public static int conversionCost = CONVERSION_COST;
    }
} 