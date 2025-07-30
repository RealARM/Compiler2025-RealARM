package Backend.Armv8.Structure;

import java.util.*;

import Backend.Armv8.Armv8Visitor;
import Backend.Armv8.Instruction.Armv8Fmov;
import Backend.Armv8.Instruction.Armv8Move;
import Backend.Armv8.Instruction.Armv8Load;
import Backend.Armv8.Instruction.Armv8Store;
import Backend.Armv8.Operand.*;
import IR.Type.FloatType;
import IR.Value.*;

public class Armv8Function {
    private String name;
    private ArrayList<Armv8Block> blocks = new ArrayList<>();
    private LinkedList<Armv8Block> blockList = new LinkedList<>();
    private Long stackSize = 0L;
    private Armv8Stack stackSpace = new Armv8Stack();
    private LinkedHashMap<Value, Long> stack = new LinkedHashMap<>();
    private ArrayList<Armv8Reg> paraReg2Stack = new ArrayList<>();

    private HashMap<Value, Armv8Reg> RegArgList = new HashMap<>();
    private HashMap<Value, Long> stackArgList = new HashMap<>();
    private HashMap<Value, Long> FPArgList = new HashMap<>();
    private Function irFunction;


    public Armv8Function(String name, Function irFunction) {
        this.name = name;
        this.irFunction = irFunction;
        
        // 处理函数序言的传参问题
        List<Argument> arguments = irFunction.getArguments();
        int argCount = irFunction.getArgumentCount();
        
        // 重要：清除寄存器映射，确保每个函数有自己独立的参数空间
        RegArgList.clear();
        stackArgList.clear();
        
        int intArgCount = 0;     // 整型参数计数器
        int floatArgCount = 0;   // 浮点参数计数器
        long stackOffset = 16;   // 栈参数偏移，从16开始，因为前16字节是保存的FP和LR
        
        // 记录函数的参数数量和类型，用于生成序言代码
        for (int i = 0; i < argCount; i++) {
            Value arg = arguments.get(i);
            boolean isFloat = arg.getType() instanceof FloatType;
            
            // 检查是否需要使用栈传递
            boolean useStack = false;
            if (isFloat) {
                useStack = floatArgCount >= 8; // 超过8个浮点参数使用栈
                floatArgCount++;
            } else {
                useStack = intArgCount >= 8;   // 超过8个整型参数使用栈
                intArgCount++;
            }
            
            if (!useStack) {
                Armv8Reg argReg;
                if (isFloat) {
                    // 浮点参数使用v0-v7寄存器
                    argReg = Armv8FPUReg.getArmv8FArgReg(floatArgCount-1);
                } else {
                    // 整数参数使用x0-x7寄存器
                    argReg = Armv8CPUReg.getArmv8ArgReg(intArgCount-1);
                }

                //将参数寄存器加入保护行列
                paraReg2Stack.add(argReg);
                // 直接建立映射关系，无需复制
                addRegArg(arg, argReg);
                Armv8Visitor.getRegList().put(arg, argReg);
            } else {
                // 栈传递参数使用FP相对寻址
                addStackArg(arg, stackOffset);
                Armv8Visitor.getPtrList().put(arg, stackOffset);
                stackOffset += 8; // 每个参数占8字节
            }
        }

        // 保护空间，保存参数寄存器和返回地址
        this.stackSize += 8 * (paraReg2Stack.size() + 32);
        this.stackSpace.addOffset(8 * (paraReg2Stack.size() + 32));
    }

    public void saveCallerRegs(Armv8Block block) {
        for(int i = 0; i < paraReg2Stack.size(); i++) {
            Armv8Reg reg = paraReg2Stack.get(i);
            block.addArmv8Instruction(new Armv8Store(reg, Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(i*8)));
        }
    }

    public void loadCallerRegs(Armv8Block block) {
        for(int i = 0; i < paraReg2Stack.size(); i++) {
            Armv8Reg reg = paraReg2Stack.get(i);
            block.addArmv8Instruction(new Armv8Load(Armv8CPUReg.getArmv8SpReg(), new Armv8Imm(i*8), reg));
        }
    }

    public void saveCalleeRegs(Armv8Block block) {
        int size = paraReg2Stack.size();
        for(int i = 0; i < 8; i++) {
            Armv8Reg reg = Armv8CPUReg.getArmv8CPUReg(i + 8);
            block.addArmv8Instruction(new Armv8Store(reg, Armv8CPUReg.getArmv8SpReg(), new Armv8Imm((i + size) * 8)));
        } 

        for (int i = 0; i < 24; i++) {
            Armv8Reg reg = Armv8FPUReg.getArmv8FloatReg(i + 8);
            block.addArmv8Instruction(new Armv8Store(reg, Armv8CPUReg.getArmv8SpReg(), new Armv8Imm((i + size + 8) * 8)));
        }
    }

    public void loadCalleeRegs(Armv8Block block) {
        int size = paraReg2Stack.size();
        for(int i = 0; i < 8; i++) {
            Armv8Reg reg = Armv8CPUReg.getArmv8CPUReg(i + 8);
            block.addArmv8Instruction(new Armv8Load(Armv8CPUReg.getArmv8SpReg(), new Armv8Imm((i + size) * 8), reg));
        } 

        for (int i = 0; i < 24; i++) {
            Armv8Reg reg = Armv8FPUReg.getArmv8FloatReg(i + 8);
            block.addArmv8Instruction(new Armv8Load(Armv8CPUReg.getArmv8SpReg(), new Armv8Imm((i + size + 8) * 8), reg));
        }
    }

    public void addStack(Value value, Long offset) {
        // 只有在指令真正需要栈空间时才分配
        // 如果offset为0，不需要增加栈大小
        if (offset <= 0) {
            return;
        }
        
        // 只有在value不为null时才添加到栈映射
        if (value != null) {
            this.stack.put(value, stackSize);
        }
        this.stackSize += offset;
        this.stackSpace.addOffset(offset);
    }

    public void addRegArg(Value arg, Armv8Reg value) {
        this.RegArgList.put(arg, value);
    }

    public void addStackArg(Value arg, Long offset) {
        this.stackArgList.put(arg, offset);
    }
    
    public Armv8Stack getStackSpace() {
        return this.stackSpace;
    }

    public void addBlock(Armv8Block block) {
        this.blocks.add(block);
        this.blockList.add(block);
    }

    public void addBlock(int index, Armv8Block block) {
        this.blocks.add(index, block);
    }

    public String getName() {
        return this.name;
    }

    public ArrayList<Armv8Block> getBlocks() {
        return this.blocks;
    }

    public LinkedList<Armv8Block> getBlockList() {
        return this.blockList;
    }

    public Long getStackSize() {
        return this.stackSize;
    }

    public LinkedHashMap<Value, Long> getStack() {
        return this.stack;
    }

    public Armv8Reg getRegArg(Value arg) {
        return this.RegArgList.get(arg);
    }

    public Long getStackArg(Value arg) {
        return this.stackArgList.get(arg);
    }
    public Long getFPArg(Value arg) { return this.FPArgList.get(arg); }
    // 在ARMv8中，被调用者保存的寄存器是x19-x29和SP
    // 需要在函数开始时保存这些寄存器，并在返回前恢复
    private String generatePrologue() {
        StringBuilder sb = new StringBuilder();
        
        // 1. 保存帧指针(x29)和返回地址(x30/LR)
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        
        // 2. 设置新的帧指针
        sb.append("\tmov x29, sp\n");
        
        // 3. 为局部变量分配栈空间（16字节对齐）
        if (stackSize > 0) {
            // 计算需要为栈分配的空间（向上取整到16的倍数）
            long alignedSize = (stackSize + 15) & ~15;  // 对齐到16字节
            
            // 检查栈大小是否超出ARMv8指令的立即数范围(4095)
            if (alignedSize > 4095) {
                // 如果超出范围，使用临时寄存器
                sb.append("\tmov x8, #").append(alignedSize).append("\n");
                sb.append("\tsub sp, sp, x8\n");
            } else {
                // 在范围内直接使用立即数
                sb.append("\tsub sp, sp, #").append(alignedSize).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    private String generateEpilogue() {
        StringBuilder sb = new StringBuilder();
        
        // 1. 恢复栈指针
        if (stackSize > 0) {
            // 计算对齐后的栈大小
            long alignedSize = (stackSize + 15) & ~15;
            
            // 检查栈大小是否超出ARMv8指令的立即数范围(4095)
            if (alignedSize > 4095) {
                // 如果超出范围，使用临时寄存器
                sb.append("\tmov x8, #").append(alignedSize).append("\n");
                sb.append("\tadd sp, sp, x8\n");
            } else {
                // 在范围内直接使用立即数
                sb.append("\tadd sp, sp, #").append(alignedSize).append("\n");
            }
        }
        
        // 2. 恢复帧指针和返回地址，同时调整栈指针
        sb.append("\tldp x29, x30, [sp], #16\n");
        
        // 3. 返回
        sb.append("\tret\n");
        
        return sb.toString();
    }
    
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(".global ").append(name).append("\n");
        sb.append(name).append(":\n");
        
        // 生成函数序言（保存寄存器，分配栈空间）
        sb.append(generatePrologue());
        
        // 保存参数到栈上（如果需要）
        // 我们需要为函数参数在栈上保留空间，或者移动到适当的寄存器
        
        // 输出基本块
        for (Armv8Block block : blocks) {
            sb.append(block.toString());
        }
        
        // 检查所有块是否有返回指令
        boolean hasRetInstruction = false;
        for (Armv8Block block : blocks) {
            if (block.hasReturnInstruction()) {
                hasRetInstruction = true;
                break;
            }
        }
        
        // 如果没有找到返回指令，生成结语
        if (!hasRetInstruction) {
            sb.append(generateEpilogue());
        }
        
        return sb.toString();
    }

    public static String generateMemsetFunction() {
        StringBuilder sb = new StringBuilder();
        sb.append(".global memset\n");
        sb.append("memset:\n");

        // 函数序言
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        sb.append("\tmov x29, sp\n");

        // 保存原始指针 s 到 x3，用于最后返回
        sb.append("\tmov x3, x0\n");

        // 将参数 c 保留在 x4，注意这里只使用低 8 位（strb 只用最低字节）
        sb.append("\tmov x4, x1\n");

        // 字节数 n 存入 x5
        sb.append("\tmov x5, x2\n");

        // 判断 n 是否为 0
        sb.append("\tcmp x5, #0\n");
        sb.append("\tbeq .Lmemset_done\n");

        // 循环计数器 i
        sb.append("\tmov x6, #0\n");

        sb.append(".Lmemset_loop:\n");
        sb.append("\tcmp x6, x5\n");
        sb.append("\tbge .Lmemset_done\n");

        sb.append("\tadd x7, x3, x6\n");     // 当前地址：s + i
        sb.append("\tstrb w4, [x7]\n");      // 存储低 8 位（自动截断 x4）

        sb.append("\tadd x6, x6, #1\n");     // i++
        sb.append("\tb .Lmemset_loop\n");

        sb.append(".Lmemset_done:\n");
        sb.append("\tmov x0, x3\n");         // 返回原始指针 s

        // 函数结尾
        sb.append("\tldp x29, x30, [sp], #16\n");
        sb.append("\tret\n");

        return sb.toString();
    }
} 