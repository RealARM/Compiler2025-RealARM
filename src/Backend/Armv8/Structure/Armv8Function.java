package Backend.Armv8.Structure;

import java.util.*;

import Backend.Armv8.Armv8Visitor;
import Backend.Armv8.Instruction.Armv8Fmov;
import Backend.Armv8.Instruction.Armv8Move;
import Backend.Armv8.Operand.*;
import IR.Type.FloatType;
import IR.Value.*;

public class Armv8Function {
    private String name;
    private ArrayList<Armv8Block> blocks = new ArrayList<>();
    private LinkedList<Armv8Block> blockList = new LinkedList<>();
    private Long stackSize = 0L;
    private LinkedHashMap<Value, Long> stack = new LinkedHashMap<>();

    private HashMap<Value, Armv8Reg> RegArgList = new HashMap<>();
    private HashMap<Value, Long> stackArgList = new HashMap<>();
    private HashMap<Value, Long> FPArgList = new HashMap<>();
    private Function irFunction;


    public Armv8Function(String name, Function irFunction) {
        this.name = name;
        this.irFunction = irFunction;
        
        //处理函数序言的传参问题
        List<Argument> arguments = irFunction.getArguments();
        int argCount = irFunction.getArgumentCount();
        
        int intArgCount = 0;     // 整型参数计数器
        int floatArgCount = 0;   // 浮点参数计数器
        long stackOffset = 0;     // 栈参数偏移
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
                    // 浮点参数使用v0-v7寄存器，使用floatArgCount-1是因为已经自增了
                    argReg = Armv8FPUReg.getArmv8FArgReg(floatArgCount-1);
                } else {
                    // 整数参数使用x0-x7寄存器，使用intArgCount-1是因为已经自增了
                    argReg = Armv8CPUReg.getArmv8ArgReg(intArgCount-1);
                }
                addRegArg(arg,argReg);
                Armv8Visitor.getRegList().put(arg, argReg);
            } else {
                // 使用栈传递参
                stackArgList.put(arg, stackOffset);
                Armv8Visitor.getPtrList().put(arg, stackOffset);
                stackOffset += 8;
            }
        }
    }

    public void addStack(Value value, Long offset) {
        this.stack.put(value, stackSize);
        this.stackSize += offset;
    }

    public void addRegArg(Value arg, Armv8Reg value) {
        this.RegArgList.put(arg, value);
    }

    public void addStackArg(Value arg, Long offset) {
        this.stackArgList.put(arg, offset);
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
        // 保存被调用者保存的寄存器和链接寄存器
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        // 设置帧指针
        sb.append("\tmov x29, sp\n");
        // 如果需要，分配栈空间
        if (stackSize > 0) {
            sb.append("\tsub sp, sp, #").append(stackSize).append("\n");
        }
        return sb.toString();
    }
    
    private String generateEpilogue() {
        StringBuilder sb = new StringBuilder();
        // 如果栈指针被修改，恢复它
        if (stackSize > 0) {
            sb.append("\tadd sp, sp, #").append(stackSize).append("\n");
        }
        // 恢复被调用者保存的寄存器和链接寄存器
        sb.append("\tldp x29, x30, [sp], #16\n");
        sb.append("\tret\n");
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
    
        sb.append("\tadd x7, x0, x6\n");     // 当前地址：s + i
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
    


    

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(".global ").append(name).append("\n");
        sb.append(name).append(":\n");
        
        // 生成函数序言（保存寄存器，分配栈空间）
        sb.append(generatePrologue());
        
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
} 