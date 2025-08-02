package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.Instructions.Instruction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public class Function extends Value {
    private final List<BasicBlock> blocks = new ArrayList<>();
    private final List<Argument> arguments = new ArrayList<>();
    private boolean isExternal;
    private Type returnType;
    
    private final List<Function> callers = new ArrayList<>();
    private final List<Function> callees = new ArrayList<>();
    
    private final LinkedHashSet<GlobalVariable> loadGVs = new LinkedHashSet<>();
    private final LinkedHashSet<GlobalVariable> storeGVs = new LinkedHashSet<>();
    
    private boolean mayHaveSideEffect = false;
    private boolean storeGlobalVar = false;
    private boolean storeArgument = false;
    
    private BasicBlock exitBlock;
    private LinkedHashMap<BasicBlock, ArrayList<BasicBlock>> immediateDominators;
    
    public Function(String name, Type returnType) {
        super(name, returnType);
        this.returnType = returnType;
        this.isExternal = false;
    }
    
    public Type getReturnType() {
        return returnType;
    }
    
    public void addArgument(Argument argument) {
        arguments.add(argument);
    }
    
    public List<Argument> getArguments() {
        return arguments;
    }
    
    public int getArgumentCount() {
        return arguments.size();
    }
    
    public Argument getArgument(int index) {
        return arguments.get(index);
    }
    
    public void addBasicBlock(BasicBlock block) {
        blocks.add(block);
    }
    
    public void addBasicBlockBefore(BasicBlock newBlock, BasicBlock before) {
        int index = blocks.indexOf(before);
        if (index != -1) {
            blocks.add(index, newBlock);
        } else {
            blocks.add(newBlock);
        }
    }
    
    public void removeBasicBlock(BasicBlock block) {
        blocks.remove(block);
    }
    
    public List<BasicBlock> getBasicBlocks() {
        return blocks;
    }
    
    public BasicBlock getEntryBlock() {
        if (blocks.isEmpty()) {
            return null;
        }
        return blocks.get(0);
    }
    
    public BasicBlock getExitBlock() {
        return exitBlock;
    }
    
    public void setExitBlock(BasicBlock block) {
        this.exitBlock = block;
    }
    
    public void setExternal(boolean external) {
        isExternal = external;
    }
    
    public boolean isExternal() {
        return isExternal;
    }
    
    public void addCaller(Function caller) {
        if (!callers.contains(caller)) {
            callers.add(caller);
        }
    }
    
    public void addCallee(Function callee) {
        if (!callees.contains(callee)) {
            callees.add(callee);
        }
    }
    
    public List<Function> getCallers() {
        return callers;
    }
    
    public List<Function> getCallees() {
        return callees;
    }
    
    public void setMayHaveSideEffect(boolean mayHaveSideEffect) {
        this.mayHaveSideEffect = mayHaveSideEffect;
    }
    
    public boolean mayHaveSideEffect() {
        return mayHaveSideEffect;
    }
    
    public void setStoreGlobalVar(boolean storeGlobalVar) {
        this.storeGlobalVar = storeGlobalVar;
    }
    
    public boolean storesGlobalVar() {
        return storeGlobalVar;
    }
    
    public void setStoreArgument(boolean storeArgument) {
        this.storeArgument = storeArgument;
    }
    
    public boolean storesArgument() {
        return storeArgument;
    }
    
    public void addLoadGlobalVar(GlobalVariable var) {
        loadGVs.add(var);
    }
    
    public void addStoreGlobalVar(GlobalVariable var) {
        storeGVs.add(var);
    }
    
    public LinkedHashSet<GlobalVariable> getLoadGlobalVars() {
        return loadGVs;
    }
    
    public LinkedHashSet<GlobalVariable> getStoreGlobalVars() {
        return storeGVs;
    }
    
    public void setImmediateDominators(LinkedHashMap<BasicBlock, ArrayList<BasicBlock>> dominators) {
        this.immediateDominators = dominators;
    }
    
    public LinkedHashMap<BasicBlock, ArrayList<BasicBlock>> getImmediateDominators() {
        return immediateDominators;
    }
    
    public int getLoopDepth(BasicBlock block) {
        return block.getLoopDepth();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("define ").append(returnType).append(" @")
            .append(getName()).append("(");
        
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(arguments.get(i));
        }
        
        sb.append(")");
        
        if (isExternal) {
            return sb.toString();
        }
        
        sb.append(" {\n");
        for (BasicBlock block : blocks) {
            sb.append("  ").append(block).append("\n");
            for (Instruction inst : block.getInstructions()) {
                sb.append("    ").append(inst).append("\n");
            }
        }
        sb.append("}");
        
        return sb.toString();
    }
} 