package MiddleEnd.Optimization.Global;

import MiddleEnd.IR.Module;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局变量局部化优化Pass
 * 将不必要的全局变量转换为局部变量或常量
 */
public class GlobalValueLocalize implements Optimizer.ModuleOptimizer {

    private final boolean DEBUG = false;

    @Override
    public String getName() {
        return "GlobalValueLocalize";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        if (DEBUG) {
            System.out.println("\n========== GlobalValueLocalize Pass 开始 ==========");
            System.out.println("优化前全局变量数量: " + module.globalVars().size());
            for (GlobalVariable gv : module.globalVars()) {
                System.out.println("  全局变量: " + gv.getName() + ", 类型: " + gv.getType());
            }
        }
        
        ArrayList<GlobalVariable> globalVars = module.globalVars();
        ArrayList<GlobalVariable> toRemove = new ArrayList<>();
        
        for (GlobalVariable globalVar : new ArrayList<>(globalVars)) {
            if (globalVar.isArray()) {
                if (DEBUG) {
                    System.out.println("跳过数组类型全局变量: " + globalVar.getName());
                }
                continue;
            }
            
            if (DEBUG) {
                System.out.println("\n分析全局变量: " + globalVar.getName());
            }
            
            GlobalVarUsageInfo usageInfo = analyzeGlobalVarUsage(globalVar, module);
            
            if (DEBUG) {
                System.out.println("  被写入: " + usageInfo.hasStore);
                System.out.println("  使用该变量的函数数量: " + usageInfo.useFuncs.size());
                for (Function func : usageInfo.useFuncs) {
                    System.out.println("    - 函数: " + func.getName());
                }
                System.out.println("  使用该变量的指令数量: " + usageInfo.useInsts.size());
            }
            
            if (!usageInfo.hasStore) {
                if (DEBUG) {
                    System.out.println("  尝试处理只读全局变量: " + globalVar.getName());
                }
                
                if (handleConstantGlobalVar(globalVar, usageInfo, module)) {
                    changed = true;
                    toRemove.add(globalVar);
                    
                    if (DEBUG) {
                        System.out.println("  成功将只读全局变量 " + globalVar.getName() + " 替换为常量");
                    }
                } else if (DEBUG) {
                    System.out.println("  无法处理全局变量: " + globalVar.getName());
                }
            }
            else if (usageInfo.useFuncs.size() == 1 && usageInfo.useFuncs.get(0).getName().equals("main")) {
                if (DEBUG) {
                    System.out.println("  尝试将仅在main中使用的全局变量 " + globalVar.getName() + " 提升为局部变量");
                }
                
                if (promoteToLocalVar(globalVar, usageInfo.useFuncs.get(0))) {
                    changed = true;
                    toRemove.add(globalVar);
                    
                    if (DEBUG) {
                        System.out.println("  成功将全局变量 " + globalVar.getName() + " 提升为main函数中的局部变量");
                    }
                } else if (DEBUG) {
                    System.out.println("  无法将全局变量提升为局部变量: " + globalVar.getName());
                }
            }
            
            if (usageInfo.useInsts.isEmpty()) {
                if (DEBUG) {
                    System.out.println("  全局变量 " + globalVar.getName() + " 完全没有被使用，将被移除");
                }
                toRemove.add(globalVar);
                changed = true;
            }
        }
        
        if (DEBUG) {
            System.out.println("\n将移除以下全局变量:");
            for (GlobalVariable gv : toRemove) {
                System.out.println("  - " + gv.getName());
            }
        }
        
        for (GlobalVariable gv : toRemove) {
            globalVars.remove(gv);
        }
        
        if (DEBUG) {
            System.out.println("\n优化后全局变量数量: " + module.globalVars().size());
            for (GlobalVariable gv : module.globalVars()) {
                System.out.println("  全局变量: " + gv.getName() + ", 类型: " + gv.getType());
            }
            System.out.println("========== GlobalValueLocalize Pass 结束 ==========\n");
        }
        
        return changed;
    }
    
    private GlobalVarUsageInfo analyzeGlobalVarUsage(GlobalVariable globalVar, Module module) {
        GlobalVarUsageInfo info = new GlobalVarUsageInfo();
        
        for (Function func : module.functions()) {
            boolean usedInFunc = false;
            
            for (BasicBlock bb : func.getBasicBlocks()) {
                for (Instruction inst : bb.getInstructions()) {
                    for (int i = 0; i < inst.getOperandCount(); i++) {
                        if (inst.getOperand(i) == globalVar) {
                            if (inst instanceof StoreInstruction && 
                                ((StoreInstruction) inst).getPointer() == globalVar) {
                                info.hasStore = true;
                                if (DEBUG) {
                                    System.out.println("    发现写入操作: " + inst);
                                }
                            }
                            
                            usedInFunc = true;
                            
                            info.useInsts.add(inst);
                            if (DEBUG) {
                                System.out.println("    发现使用指令: " + inst + " 在函数 " + func.getName());
                            }
                        }
                    }
                }
            }
            
            if (usedInFunc) {
                info.useFuncs.add(func);
            }
        }
        
        return info;
    }
    
    private boolean handleConstantGlobalVar(GlobalVariable globalVar, GlobalVarUsageInfo usageInfo, Module module) {
        Value initValue = globalVar.getInitializer();
        if (initValue == null) {
            if (DEBUG) {
                System.out.println("    全局变量 " + globalVar.getName() + " 没有初始值，无法处理");
            }
            return false;
        }
        
        Type elementType = null;
        if (globalVar.getType() instanceof PointerType) {
            elementType = ((PointerType) globalVar.getType()).getElementType();
        } else {
            if (DEBUG) {
                System.out.println("    全局变量 " + globalVar.getName() + " 不是指针类型，无法处理");
            }
            return false;
        }
        
        boolean changed = false;
        
        for (Instruction inst : new ArrayList<>(usageInfo.useInsts)) {
            if (inst instanceof LoadInstruction loadInst && loadInst.getPointer() == globalVar) {
                Constant newValue = null;
                
                if (initValue instanceof ConstantInt) {
                    int initVal = ((ConstantInt) initValue).getValue();
                    newValue = new ConstantInt(initVal);
                    if (DEBUG) {
                        System.out.println("    创建整数常量: " + initVal);
                    }
                }
                else if (initValue instanceof ConstantFloat) {
                    double initVal = ((ConstantFloat) initValue).getValue();
                    newValue = new ConstantFloat(initVal);
                    if (DEBUG) {
                        System.out.println("    创建浮点常量: " + initVal);
                    }
                }
                
                if (newValue != null) {
                    if (DEBUG) {
                        System.out.println("    替换load指令: " + loadInst + " 为常量: " + newValue);
                    }
                    
                    replaceAllUses(loadInst, newValue);
                    
                    loadInst.removeFromParent();
                    changed = true;
                }
            }
        }
        
        return changed;
    }
    
    private boolean promoteToLocalVar(GlobalVariable globalVar, Function mainFunc) {
        if (mainFunc.getBasicBlocks().isEmpty()) {
            if (DEBUG) {
                System.out.println("    main函数没有基本块，无法提升全局变量");
            }
            return false;
        }
        
        BasicBlock entryBB = mainFunc.getBasicBlocks().get(0);
        
        Type elementType = null;
        if (globalVar.getType() instanceof PointerType) {
            elementType = ((PointerType) globalVar.getType()).getElementType();
        } else {
            if (DEBUG) {
                System.out.println("    全局变量 " + globalVar.getName() + " 不是指针类型，无法处理");
            }
            return false;
        }
        
        Value initValue = globalVar.getInitializer();
        if (initValue == null) {
            if (DEBUG) {
                System.out.println("    全局变量 " + globalVar.getName() + " 没有初始值，无法处理");
            }
            return false;
        }
        
        String localName = "local." + globalVar.getName().substring(1);
        AllocaInstruction allocaInst = new AllocaInstruction(elementType, localName);
        
        if (DEBUG) {
            System.out.println("    创建局部变量: " + allocaInst);
        }
        
        StoreInstruction storeInst = null;
        if (initValue instanceof ConstantInt) {
            int initVal = ((ConstantInt) initValue).getValue();
            storeInst = new StoreInstruction(new ConstantInt(initVal), allocaInst);
        } else if (initValue instanceof ConstantFloat) {
            double initVal = ((ConstantFloat) initValue).getValue();
            storeInst = new StoreInstruction(new ConstantFloat(initVal), allocaInst);
        } else {
            if (DEBUG) {
                System.out.println("    不支持的初始值类型: " + initValue.getClass().getSimpleName());
            }
            return false;
        }
        
        if (DEBUG) {
            System.out.println("    创建存储指令: " + storeInst);
        }
        
        List<Instruction> instructions = entryBB.getInstructions();
        if (instructions.isEmpty()) {
            if (DEBUG) {
                System.out.println("    入口基本块为空，直接添加指令");
            }
            entryBB.addInstruction(allocaInst);
            entryBB.addInstruction(storeInst);
        } else {
            if (DEBUG) {
                System.out.println("    在入口基本块的第一条指令前插入");
            }
            entryBB.addInstructionBefore(allocaInst, instructions.get(0));
            entryBB.addInstructionBefore(storeInst, instructions.get(0));
        }
        
        if (DEBUG) {
            System.out.println("    替换全局变量的所有使用为局部变量");
        }
        replaceAllUses(globalVar, allocaInst);
        
        return true;
    }
    
    private void replaceAllUses(Value oldValue, Value newValue) {
        List<User> users = new ArrayList<>(oldValue.getUsers());
        
        if (DEBUG) {
            System.out.println("    替换 " + oldValue + " 的所有使用为 " + newValue);
            System.out.println("    使用者数量: " + users.size());
        }
        
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldValue) {
                    if (DEBUG) {
                        System.out.println("      替换在 " + user + " 中的使用");
                    }
                    user.setOperand(i, newValue);
                }
            }
        }
    }
    
    private static class GlobalVarUsageInfo {
        boolean hasStore = false;
        List<Function> useFuncs = new ArrayList<>();
        List<Instruction> useInsts = new ArrayList<>();
    }
}