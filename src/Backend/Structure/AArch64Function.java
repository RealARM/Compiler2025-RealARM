package Backend.Structure;

import java.util.*;

import Backend.AArch64Visitor;
import Backend.Value.Base.AArch64Operand;
import Backend.Value.Instruction.Arithmetic.AArch64Binary;
import Backend.Value.Instruction.DataMovement.AArch64Move;
import Backend.Value.Instruction.Memory.AArch64Load;
import Backend.Value.Instruction.Memory.AArch64Store;
import Backend.Value.Operand.Constant.AArch64Imm;
import Backend.Value.Operand.Memory.AArch64Stack;
import Backend.Value.Operand.Register.AArch64CPUReg;
import Backend.Value.Operand.Register.AArch64FPUReg;
import Backend.Value.Operand.Register.AArch64Reg;
import Backend.Value.Operand.Register.AArch64VirReg;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.Value.*;

public class AArch64Function {
    private String name;
    private ArrayList<AArch64Block> blocks = new ArrayList<>();
    private LinkedList<AArch64Block> blockList = new LinkedList<>();
    private Long stackSize = 0L;
    private AArch64Stack stackSpace = new AArch64Stack();
    private LinkedHashMap<Value, Long> stack = new LinkedHashMap<>();
    private ArrayList<AArch64Reg> callerReg = new ArrayList<>();

    private HashMap<Value, AArch64Reg> RegArgList = new HashMap<>();
    private HashMap<Value, Long> stackArgList = new HashMap<>();
    private HashMap<Value, Long> FPArgList = new HashMap<>();
    private Function irFunction;


    public AArch64Function(String name, Function irFunction) {
        this.name = name;
        this.irFunction = irFunction;
        
        List<Argument> arguments = irFunction.getArguments();
        int argCount = irFunction.getArgumentCount();
        
        RegArgList.clear();
        stackArgList.clear();
        
        int intArgCount = 0;     // 整型参数计数器
        int floatArgCount = 0;   // 浮点参数计数器
        long stackOffset = 16;   // 栈参数偏移，从16开始，因为前16字节是保存的FP和LR
        
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
                AArch64Reg argReg;
                if (isFloat) {
                    argReg = AArch64FPUReg.getAArch64FArgReg(floatArgCount-1);
                } else {
                    argReg = AArch64CPUReg.getAArch64ArgReg(intArgCount-1);
                }

                //将参数寄存器加入保护行列
                callerReg.add(argReg);
                addRegArg(arg, argReg);
                AArch64Visitor.getRegList().put(arg, argReg);
            } else {
                // 栈传递参数使用FP相对寻址
                addStackArg(arg, stackOffset);
                AArch64Visitor.getPtrList().put(arg, stackOffset);
                stackOffset += 8;
            }
        }

        // 保护空间，保存参数寄存器和返回地址
        this.stackSize += 8 * (callerReg.size() + 32);
        this.stackSpace.addOffset(8 * (callerReg.size() + 32));
    }

    private void createSafeMemoryInstruction(AArch64Block block, AArch64Reg baseReg, long offset, AArch64Reg valueReg, boolean isLoad) {
        // 检查偏移量是否在ARMv8内存指令的范围内
        // ARMv8 LDR/STR指令支持：
        // 1. 9位有符号偏移：-256到255
        // 2. 12位无符号偏移：0到32760，且必须是8的倍数
        
        if (offset >= -256 && offset <= 255) {
            if (isLoad) {
                block.addAArch64Instruction(new AArch64Load(baseReg, new AArch64Imm(offset), valueReg));
            } else {
                block.addAArch64Instruction(new AArch64Store(valueReg, baseReg, new AArch64Imm(offset)));
            }
        } else if (offset >= 0 && offset <= 32760 && (offset % 8 == 0)) {
            if (isLoad) {
                block.addAArch64Instruction(new AArch64Load(baseReg, new AArch64Imm(offset), valueReg));
            } else {
                block.addAArch64Instruction(new AArch64Store(valueReg, baseReg, new AArch64Imm(offset)));
            }
        } else {
            AArch64VirReg tempAddrReg = new AArch64VirReg(false);
            
            loadLargeImmediate(block, tempAddrReg, offset);
            
            ArrayList<AArch64Operand> addOperands = new ArrayList<>();
            addOperands.add(baseReg);
            addOperands.add(tempAddrReg);
            AArch64Binary addInst = new AArch64Binary(addOperands, tempAddrReg, AArch64Binary.AArch64BinaryType.add);
            block.addAArch64Instruction(addInst);
            
            // 使用计算得到的地址进行内存访问
            if (isLoad) {
                block.addAArch64Instruction(new AArch64Load(tempAddrReg, new AArch64Imm(0), valueReg));
            } else {
                block.addAArch64Instruction(new AArch64Store(valueReg, tempAddrReg, new AArch64Imm(0)));
            }
        }
    }
    private void loadLargeImmediate(AArch64Block block, AArch64Reg destReg, long value) {
        long bits = value;
        
        // 使用MOVZ指令加载第一个16位
        AArch64Move movzInst = new AArch64Move(destReg, new AArch64Imm(bits & 0xFFFF), true, AArch64Move.MoveType.MOVZ);
        block.addAArch64Instruction(movzInst);
        
        // 检查第二个16位（bits[31:16]）
        if (((bits >> 16) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 16) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(16);
            block.addAArch64Instruction(movkInst);
        }
        
        // 检查第三个16位（bits[47:32]）
        if (((bits >> 32) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 32) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(32);
            block.addAArch64Instruction(movkInst);
        }
        
        // 检查第四个16位（bits[63:48]）
        if (((bits >> 48) & 0xFFFF) != 0) {
            AArch64Move movkInst = new AArch64Move(destReg, new AArch64Imm((bits >> 48) & 0xFFFF), true, AArch64Move.MoveType.MOVK);
            movkInst.setShift(48);
            block.addAArch64Instruction(movkInst);
        }
    }

    public void saveCallerRegs(AArch64Block block) {
        for(int i = 0; i < callerReg.size(); i++) {
            AArch64Reg reg = callerReg.get(i);
            long offset = i * 8L;
            createSafeMemoryInstruction(block, AArch64CPUReg.getAArch64SpReg(), offset, reg, false);
        }
    }

    public void loadCallerRegs(AArch64Block block) {
        for(int i = 0; i < callerReg.size(); i++) {
            AArch64Reg reg = callerReg.get(i);
            long offset = i * 8L;
            createSafeMemoryInstruction(block, AArch64CPUReg.getAArch64SpReg(), offset, reg, true);
        }
    }

    public void saveCalleeRegs(AArch64Block block) {
        int size = callerReg.size();
        for(int i = 0; i < 8; i++) {
            AArch64Reg reg = AArch64CPUReg.getAArch64CPUReg(i + 19);
            long offset = (i + size) * 8L;
            createSafeMemoryInstruction(block, AArch64CPUReg.getAArch64SpReg(), offset, reg, false);
        } 

        for (int i = 0; i < 24; i++) {
            AArch64Reg reg = AArch64FPUReg.getAArch64FloatReg(i + 8);
            long offset = (i + size + 8) * 8L;
            createSafeMemoryInstruction(block, AArch64CPUReg.getAArch64SpReg(), offset, reg, false);
        }
    }

    public void loadCalleeRegs(AArch64Block block) {
        int size = callerReg.size();
        for(int i = 0; i < 8; i++) {
            AArch64Reg reg = AArch64CPUReg.getAArch64CPUReg(i + 19);
            long offset = (i + size) * 8L;
            createSafeMemoryInstruction(block, AArch64CPUReg.getAArch64SpReg(), offset, reg, true);
        } 

        for (int i = 0; i < 24; i++) {
            AArch64Reg reg = AArch64FPUReg.getAArch64FloatReg(i + 8);
            long offset = (i + size + 8) * 8L;
            createSafeMemoryInstruction(block, AArch64CPUReg.getAArch64SpReg(), offset, reg, true);
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

    public void addRegArg(Value arg, AArch64Reg value) {
        this.RegArgList.put(arg, value);
    }

    public void addStackArg(Value arg, Long offset) {
        this.stackArgList.put(arg, offset);
    }
    
    public AArch64Stack getStackSpace() {
        return this.stackSpace;
    }

    public void addBlock(AArch64Block block) {
        this.blocks.add(block);
        this.blockList.add(block);
    }

    public void addBlock(int index, AArch64Block block) {
        this.blocks.add(index, block);
    }

    public String getName() {
        return this.name;
    }

    public ArrayList<AArch64Block> getBlocks() {
        return this.blocks;
    }

    public LinkedList<AArch64Block> getBlockList() {
        return this.blockList;
    }

    public Long getStackSize() {
        return this.stackSize;
    }

    public LinkedHashMap<Value, Long> getStack() {
        return this.stack;
    }

    public AArch64Reg getRegArg(Value arg) {
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
                // 如果超出范围，使用临时寄存器，需要检查立即数是否超过MOV指令范围
                if (alignedSize > 65535) {
                    // 对于大于65535的值，使用movz/movk指令序列
                    sb.append("\tmovz x8, #").append(alignedSize & 0xFFFF).append("\n");
                    if (((alignedSize >> 16) & 0xFFFF) != 0) {
                        sb.append("\tmovk x8, #").append((alignedSize >> 16) & 0xFFFF).append(", lsl #16\n");
                    }
                } else {
                    // 对于较小的值，使用movz指令
                    sb.append("\tmovz x8, #").append(alignedSize).append("\n");
                }
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
                // 如果超出范围，使用临时寄存器，需要检查立即数是否超过MOV指令范围
                if (alignedSize > 65535) {
                    // 对于大于65535的值，使用movz/movk指令序列
                    sb.append("\tmovz x8, #").append(alignedSize & 0xFFFF).append("\n");
                    if (((alignedSize >> 16) & 0xFFFF) != 0) {
                        sb.append("\tmovk x8, #").append((alignedSize >> 16) & 0xFFFF).append(", lsl #16\n");
                    }
                } else {
                    // 对于较小的值，使用movz指令
                    sb.append("\tmovz x8, #").append(alignedSize).append("\n");
                }
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
        for (AArch64Block block : blocks) {
            sb.append(block.toString());
        }
        
        // 检查所有块是否有返回指令
        boolean hasRetInstruction = false;
        for (AArch64Block block : blocks) {
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