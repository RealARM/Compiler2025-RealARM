package MiddleEnd.Optimization.Instruction;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;
import MiddleEnd.IR.IRBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令组合优化
 */
public class InstCombine implements Optimizer.ModuleOptimizer {
    
    @Override
    public String getName() {
        return "InstructionCombine";
    }

    @Override
    public boolean run(Module module) {
        boolean changed = false;
        
        for (Function function : module.functions()) {
            if (function.isExternal()) {
                continue;
            }
            
            changed |= runOnFunction(function);
        }
        
        return changed;
    }
    
    private boolean runOnFunction(Function function) {
        boolean changed = false;
        
        for (BasicBlock block : function.getBasicBlocks()) {
            List<Instruction> instructions = new ArrayList<>(block.getInstructions());
            
            for (Instruction inst : instructions) {
                if (inst.getParent() != block) {
                    continue;
                }
                
                if (inst instanceof BinaryInstruction binInst) {
                    boolean instChanged = optimizeBinaryInstruction(binInst);
                    changed |= instChanged;
                }
                
                if (inst instanceof CompareInstruction cmpInst) {
                    boolean instChanged = optimizeCompareInstruction(cmpInst);
                    changed |= instChanged;
                }
            }
        }
        
        return changed;
    }
    
    /**
     * 优化比较指令
     * 对形如 i * const < const 或 i + const < const 的比较指令进行优化
     * @return 如果指令被优化则返回true
     */
    private boolean optimizeCompareInstruction(CompareInstruction cmpInst) {
        Value left = cmpInst.getLeft();
        Value right = cmpInst.getRight();
        
        if (!cmpInst.isIntegerCompare()) {
            return false;
        }
        
        if (!(right instanceof ConstantInt rightConst)) {
            return false;
        }
        
        if (left instanceof BinaryInstruction binInst) {
            return optimizeCompareWithBinary(cmpInst, binInst, rightConst);
        }
        
        return false;
    }
    
    /**
     * 优化左操作数是二元指令的比较指令
     */
    private boolean optimizeCompareWithBinary(CompareInstruction cmpInst, BinaryInstruction binInst, ConstantInt rightConst) {
        Value binLeft = binInst.getOperand(0);
        Value binRight = binInst.getOperand(1);
        OpCode binOpCode = binInst.getOpCode();
        int rightValue = rightConst.getValue();
        
        if (binRight instanceof ConstantInt binRightConst) {
            int binRightValue = binRightConst.getValue();
            
            // 处理加法：(x + const1) < const2 => x < (const2 - const1)
            if (binOpCode == OpCode.ADD) {
                ConstantInt newConst = new ConstantInt(rightValue - binRightValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binLeft);
                cmpInst.setOperand(1, newConst);
                return true;
            }
            
            // 处理减法：(x - const1) < const2 => x < (const2 + const1)
            else if (binOpCode == OpCode.SUB) {
                ConstantInt newConst = new ConstantInt(rightValue + binRightValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binLeft);
                cmpInst.setOperand(1, newConst);
                return true;
            }
            
            // 处理乘法：(x * const1) < const2 => x < (const2 / const1)，仅当const1为非零且能整除时
            else if (binOpCode == OpCode.MUL && binRightValue != 0 && rightValue % binRightValue == 0) {
                ConstantInt newConst = new ConstantInt(rightValue / binRightValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binLeft);
                cmpInst.setOperand(1, newConst);
                return true;
            }
        }
        
        else if (binLeft instanceof ConstantInt binLeftConst) {
            int binLeftValue = binLeftConst.getValue();
            
            // 处理加法：(const1 + x) < const2 => x < (const2 - const1)
            if (binOpCode == OpCode.ADD) {
                ConstantInt newConst = new ConstantInt(rightValue - binLeftValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binRight);
                cmpInst.setOperand(1, newConst);
                return true;
            }
            
            // 处理乘法：(const1 * x) < const2 => x < (const2 / const1)，仅当const1为非零且能整除时
            else if (binOpCode == OpCode.MUL && binLeftValue != 0 && rightValue % binLeftValue == 0) {
                ConstantInt newConst = new ConstantInt(rightValue / binLeftValue, (IntegerType)rightConst.getType());
                cmpInst.setOperand(0, binRight);
                cmpInst.setOperand(1, newConst);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 优化二元运算指令
     * @return 如果指令被优化则返回true
     */
    private boolean optimizeBinaryInstruction(BinaryInstruction binInst) {
        OpCode opcode = binInst.getOpCode();
        Value left = binInst.getOperand(0);
        Value right = binInst.getOperand(1);
        
        // X + 0 = X
        if (opcode == OpCode.ADD && isConstantZero(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        // 0 + X = X
        if (opcode == OpCode.ADD && isConstantZero(left)) {
            replaceInstruction(binInst, right);
            return true;
        }
        
        // X - 0 = X
        if (opcode == OpCode.SUB && isConstantZero(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        // X * 1 = X
        if (opcode == OpCode.MUL && isConstantOne(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        // 1 * X = X
        if (opcode == OpCode.MUL && isConstantOne(left)) {
            replaceInstruction(binInst, right);
            return true;
        }
        
        // X * 0 = 0
        if (opcode == OpCode.MUL && (isConstantZero(left) || isConstantZero(right))) {
            Type type = binInst.getType();
            if (type instanceof IntegerType) {
                ConstantInt zero = new ConstantInt(0, (IntegerType) type);
                replaceInstruction(binInst, zero);
                return true;
            }
        }
        
        // X / 1 = X
        if (opcode == OpCode.DIV && isConstantOne(right)) {
            replaceInstruction(binInst, left);
            return true;
        }
        
        // X % const （srem） - 常量特殊优化
        if (opcode == OpCode.REM && right instanceof ConstantInt constInt && left.getType() instanceof IntegerType) {
            IntegerType intTy = (IntegerType) left.getType();
            // 仅处理 i32，其他位宽可按需扩展
            if (intTy.getBitWidth() == 32) {
                int C = constInt.getValue();
                if (C == 0) return false; // 保持原样，交由后端处理/报错
                int absC = Math.abs(C);
                
                // a % 1 = 0；a % -1 = 0
                if (absC == 1) {
                    replaceInstruction(binInst, new ConstantInt(0, IntegerType.I32));
                    return true;
                }
                
                // C = 2^k
                if (isPowerOfTwo(absC)) {
                    boolean changed = rewriteSRemByPow2(binInst, left, absC);
                    if (changed) return true;
                }
                
                // C = 2^k - 1（Mersenne）
                if (isPowerOfTwo(absC + 1)) {
                    boolean changed = rewriteSRemByMersenne(binInst, left, absC);
                    if (changed) return true;
                }
                
                // C = 2^k + 1
                if (absC > 1 && isPowerOfTwo(absC - 1)) {
                    boolean changed = rewriteSRemByFermatPlus1(binInst, left, absC);
                    if (changed) return true;
                }
            }
        }
        
        // MUL by power-of-two -> SHL (保持 i32 语义)
        if (opcode == OpCode.MUL && left.getType() instanceof IntegerType &&
            ((IntegerType) left.getType()).getBitWidth() == 32) {
            Value varOp = null;
            Integer cValObj = null;
            if (right instanceof ConstantInt rc) {
                cValObj = rc.getValue();
                varOp = left;
            } else if (left instanceof ConstantInt lc) {
                cValObj = lc.getValue();
                varOp = right;
            }
            if (cValObj != null) {
                int C = cValObj;
                int absC = Math.abs(C);
                if (absC > 0 && isPowerOfTwo(absC)) {
                    int k = log2(absC);
                    BinaryInstruction shl = IRBuilder.createBinaryInstOnly(OpCode.SHL, varOp, ci32(k));
                    shl.insertBefore(binInst);
                    if (C > 0) {
                        replaceInstruction(binInst, shl);
                    } else {
                        BinaryInstruction neg = IRBuilder.createBinaryInstOnly(OpCode.SUB, ci32(0), shl);
                        neg.insertBefore(binInst);
                        replaceInstruction(binInst, neg);
                    }
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean isConstantZero(Value value) {
        return value instanceof ConstantInt && ((ConstantInt) value).getValue() == 0;
    }
    
    private boolean isConstantOne(Value value) {
        return value instanceof ConstantInt && ((ConstantInt) value).getValue() == 1;
    }
    
    private void replaceInstruction(Instruction oldInst, Value newValue) {
        ArrayList<User> users = new ArrayList<>(oldInst.getUsers());
        
        for (User user : users) {
            for (int i = 0; i < user.getOperandCount(); i++) {
                if (user.getOperand(i) == oldInst) {
                    user.setOperand(i, newValue);
                }
            }
        }
        
        if (oldInst.getUsers().isEmpty()) {
            oldInst.removeFromParent();
        }
    }

    // ======== Helpers for srem optimizations ========

    private boolean isPowerOfTwo(int x) {
        return x > 0 && (x & (x - 1)) == 0;
    }

    private int log2(int x) {
        int r = 0;
        while ((1 << (r + 1)) <= x) r++;
        return r;
    }

    private ConstantInt ci32(int v) {
        return new ConstantInt(v, IntegerType.I32);
    }

    // srem i32 a, 2^k
    // r0 = a & (2^k-1)
    // if (r0 != 0 && a < 0) r0 -= 2^k
    private boolean rewriteSRemByPow2(BinaryInstruction binInst, Value a, int C) {
        BasicBlock block = binInst.getParent();
        ConstantInt mask = ci32(C - 1);

        BinaryInstruction r0 = IRBuilder.createBinaryInstOnly(OpCode.AND, a, mask);
        r0.insertBefore(binInst);

        // neg = (a < 0)
        CompareInstruction neg = IRBuilder.createICmp(OpCode.SLT, a, ci32(0), null);
        neg.insertBefore(binInst);
        // nz = (r0 != 0)
        CompareInstruction nz = IRBuilder.createICmp(OpCode.NE, r0, ci32(0), null);
        nz.insertBefore(binInst);
        // need = neg & nz   (i1)
        BinaryInstruction need = IRBuilder.createBinaryInstOnly(OpCode.AND, neg, nz);
        need.insertBefore(binInst);
        // need32 = zext need to i32
        ConversionInstruction need32 = IRBuilder.createZeroExtend(need, IntegerType.I32, null);
        need32.insertBefore(binInst);
        // adj = need32 * C
        BinaryInstruction adj;
        int k = log2(C);
        adj = IRBuilder.createBinaryInstOnly(OpCode.SHL, need32, ci32(k));
        adj.insertBefore(binInst);
        // res = r0 - adj
        BinaryInstruction res = IRBuilder.createBinaryInstOnly(OpCode.SUB, r0, adj);
        res.insertBefore(binInst);

        replaceInstruction(binInst, res);
        return true;
    }

    // srem i32 a, (2^k - 1), k>=1
    // Compute ru = |a| mod C via 3 folds:
    //   r = (x & C) + (x >> k); repeat twice; then if r >= C => r -= C
    // Then if a < 0: res = -ru; else res = ru
    private boolean rewriteSRemByMersenne(BinaryInstruction binInst, Value a, int C) {
        int k = log2(C + 1); // since C+1 is power of two

        // Compute xabs = abs(a) using bit tricks: mask = a>>31; xabs = (a ^ mask) - mask
        BinaryInstruction signMask = IRBuilder.createBinaryInstOnly(OpCode.ASHR, a, ci32(31));
        signMask.insertBefore(binInst);
        BinaryInstruction ax = IRBuilder.createBinaryInstOnly(OpCode.XOR, a, signMask);
        ax.insertBefore(binInst);
        BinaryInstruction xabs = IRBuilder.createBinaryInstOnly(OpCode.SUB, ax, signMask);
        xabs.insertBefore(binInst);

        ConstantInt maskC = ci32(C);
        ConstantInt shiftK = ci32(k);

        // Fold 1: r1 = (xabs & C) + (xabs lshr k)
        BinaryInstruction and1 = IRBuilder.createBinaryInstOnly(OpCode.AND, xabs, maskC);
        and1.insertBefore(binInst);
        BinaryInstruction sh1 = IRBuilder.createBinaryInstOnly(OpCode.LSHR, xabs, shiftK);
        sh1.insertBefore(binInst);
        BinaryInstruction r1 = IRBuilder.createBinaryInstOnly(OpCode.ADD, and1, sh1);
        r1.insertBefore(binInst);

        // Fold 2
        BinaryInstruction and2 = IRBuilder.createBinaryInstOnly(OpCode.AND, r1, maskC);
        and2.insertBefore(binInst);
        BinaryInstruction sh2 = IRBuilder.createBinaryInstOnly(OpCode.LSHR, r1, shiftK);
        sh2.insertBefore(binInst);
        BinaryInstruction r2 = IRBuilder.createBinaryInstOnly(OpCode.ADD, and2, sh2);
        r2.insertBefore(binInst);

        // Optionally perform Fold 3 only when needed (for small k)
        BinaryInstruction r3 = null;
        if (k < 16) {
            BinaryInstruction and3 = IRBuilder.createBinaryInstOnly(OpCode.AND, r2, maskC);
            and3.insertBefore(binInst);
            BinaryInstruction sh3 = IRBuilder.createBinaryInstOnly(OpCode.LSHR, r2, shiftK);
            sh3.insertBefore(binInst);
            r3 = IRBuilder.createBinaryInstOnly(OpCode.ADD, and3, sh3);
            r3.insertBefore(binInst);
        }

        Value reduced = (k < 16) ? r3 : r2;

        // If reduced >= C: reduced -= C
        CompareInstruction geC = IRBuilder.createICmp(OpCode.UGE, reduced, maskC, null);
        geC.insertBefore(binInst);
        ConversionInstruction geMask = IRBuilder.createZeroExtend(geC, IntegerType.I32, null);
        geMask.insertBefore(binInst);
        // Build full mask: geFull = 0 - geMask  => 0 or -1
        BinaryInstruction geFull = IRBuilder.createBinaryInstOnly(OpCode.SUB, ci32(0), geMask);
        geFull.insertBefore(binInst);
        // geAdj = geFull & C  => 0 or C
        BinaryInstruction geAdj = IRBuilder.createBinaryInstOnly(OpCode.AND, geFull, maskC);
        geAdj.insertBefore(binInst);
        BinaryInstruction ru = IRBuilder.createBinaryInstOnly(OpCode.SUB, reduced, geAdj);
        ru.insertBefore(binInst);

        // srem sign adjust: if a < 0 then -ru else ru, using xor/sub trick
        CompareInstruction neg = IRBuilder.createICmp(OpCode.SLT, a, ci32(0), null);
        neg.insertBefore(binInst);
        ConversionInstruction neg32 = IRBuilder.createZeroExtend(neg, IntegerType.I32, null);
        neg32.insertBefore(binInst);
        BinaryInstruction negFull = IRBuilder.createBinaryInstOnly(OpCode.SUB, ci32(0), neg32);
        negFull.insertBefore(binInst);
        BinaryInstruction xorRu = IRBuilder.createBinaryInstOnly(OpCode.XOR, ru, negFull);
        xorRu.insertBefore(binInst);
        BinaryInstruction res = IRBuilder.createBinaryInstOnly(OpCode.SUB, xorRu, negFull);
        res.insertBefore(binInst);

        replaceInstruction(binInst, res);
        return true;
    }

    // srem i32 a, (2^k + 1), k>=1
    // Compute ru = |a| mod C using alternating subtract folds:
    //   r = (x & (2^k-1)) - (x >> k); if r < 0 r += C; repeat twice; finally if r >= C r -= C
    // Then if a < 0: res = -ru; else ru
    private boolean rewriteSRemByFermatPlus1(BinaryInstruction binInst, Value a, int C) {
        int k = log2(C - 1); // since C-1 is power of two
        ConstantInt mask = ci32(C - 1);
        ConstantInt cConst = ci32(C);
        ConstantInt shiftK = ci32(k);

        // xabs
        BinaryInstruction signMask = IRBuilder.createBinaryInstOnly(OpCode.ASHR, a, ci32(31));
        signMask.insertBefore(binInst);
        BinaryInstruction ax = IRBuilder.createBinaryInstOnly(OpCode.XOR, a, signMask);
        ax.insertBefore(binInst);
        BinaryInstruction xabs = IRBuilder.createBinaryInstOnly(OpCode.SUB, ax, signMask);
        xabs.insertBefore(binInst);

        // Step 1: r1 = (xabs & mask) - (xabs >> k); if r1 < 0 r1 += C
        BinaryInstruction and1 = IRBuilder.createBinaryInstOnly(OpCode.AND, xabs, mask);
        and1.insertBefore(binInst);
        BinaryInstruction sh1 = IRBuilder.createBinaryInstOnly(OpCode.LSHR, xabs, shiftK);
        sh1.insertBefore(binInst);
        BinaryInstruction r1 = IRBuilder.createBinaryInstOnly(OpCode.SUB, and1, sh1);
        r1.insertBefore(binInst);
        CompareInstruction r1neg = IRBuilder.createICmp(OpCode.SLT, r1, ci32(0), null);
        r1neg.insertBefore(binInst);
        ConversionInstruction r1m = IRBuilder.createZeroExtend(r1neg, IntegerType.I32, null);
        r1m.insertBefore(binInst);
        BinaryInstruction r1adj = IRBuilder.createBinaryInstOnly(OpCode.MUL, r1m, cConst);
        r1adj.insertBefore(binInst);
        BinaryInstruction r1fix = IRBuilder.createBinaryInstOnly(OpCode.ADD, r1, r1adj);
        r1fix.insertBefore(binInst);

        // Step 2: r2 = (r1fix & mask) - (r1fix >> k); if r2 < 0 r2 += C
        BinaryInstruction and2 = IRBuilder.createBinaryInstOnly(OpCode.AND, r1fix, mask);
        and2.insertBefore(binInst);
        BinaryInstruction sh2 = IRBuilder.createBinaryInstOnly(OpCode.LSHR, r1fix, shiftK);
        sh2.insertBefore(binInst);
        BinaryInstruction r2 = IRBuilder.createBinaryInstOnly(OpCode.SUB, and2, sh2);
        r2.insertBefore(binInst);
        CompareInstruction r2neg = IRBuilder.createICmp(OpCode.SLT, r2, ci32(0), null);
        r2neg.insertBefore(binInst);
        ConversionInstruction r2m = IRBuilder.createZeroExtend(r2neg, IntegerType.I32, null);
        r2m.insertBefore(binInst);
        BinaryInstruction r2adj = IRBuilder.createBinaryInstOnly(OpCode.MUL, r2m, cConst);
        r2adj.insertBefore(binInst);
        BinaryInstruction r2fix = IRBuilder.createBinaryInstOnly(OpCode.ADD, r2, r2adj);
        r2fix.insertBefore(binInst);

        // Final tighten: if r2fix >= C r2fix -= C
        CompareInstruction geC = IRBuilder.createICmp(OpCode.SGE, r2fix, cConst, null);
        geC.insertBefore(binInst);
        ConversionInstruction geMask = IRBuilder.createZeroExtend(geC, IntegerType.I32, null);
        geMask.insertBefore(binInst);
        BinaryInstruction geAdj = IRBuilder.createBinaryInstOnly(OpCode.MUL, geMask, cConst);
        geAdj.insertBefore(binInst);
        BinaryInstruction ru = IRBuilder.createBinaryInstOnly(OpCode.SUB, r2fix, geAdj);
        ru.insertBefore(binInst);

        // srem sign adjust: if a < 0 then -ru else ru
        CompareInstruction neg = IRBuilder.createICmp(OpCode.SLT, a, ci32(0), null);
        neg.insertBefore(binInst);
        ConversionInstruction neg32 = IRBuilder.createZeroExtend(neg, IntegerType.I32, null);
        neg32.insertBefore(binInst);
        BinaryInstruction tmpNegRu = IRBuilder.createBinaryInstOnly(OpCode.SUB, ci32(0), ru);
        tmpNegRu.insertBefore(binInst);
        BinaryInstruction diff = IRBuilder.createBinaryInstOnly(OpCode.SUB, tmpNegRu, ru);
        diff.insertBefore(binInst);
        BinaryInstruction adj = IRBuilder.createBinaryInstOnly(OpCode.MUL, neg32, diff);
        adj.insertBefore(binInst);
        BinaryInstruction res = IRBuilder.createBinaryInstOnly(OpCode.ADD, ru, adj);
        res.insertBefore(binInst);

        replaceInstruction(binInst, res);
        return true;
    }
}