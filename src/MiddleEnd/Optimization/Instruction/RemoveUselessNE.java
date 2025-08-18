package MiddleEnd.Optimization.Instruction;

import MiddleEnd.IR.Module;
import MiddleEnd.Optimization.Core.Optimizer;
import MiddleEnd.Optimization.Cleanup.DCE;
import MiddleEnd.Optimization.Analysis.*;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.IR.OpCode;
import MiddleEnd.IR.Type.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 移除无用的不等于比较指令和相关无用转换指令Pass
 * 优化：
 * 1. 形如 (x != 0) 的比较指令，其中x已经是布尔值(i1类型)
 * 2. 形如 (x != 0) 的比较指令后，使用zext对结果进行扩展的模式
 */
public class RemoveUselessNE implements Optimizer.ModuleOptimizer {

	@Override
	public String getName() {
		return "RemoveUselessNE";
	}

	@Override
	public boolean run(Module module) {
		boolean changed = false;

		for (Function function : module.functions()) {
			if (function.isExternal()) {
				continue;
			}
			
			for (BasicBlock bb : function.getBasicBlocks()) {
				changed |= optimizeBasicBlock(bb);
			}
		}
		
		if (changed) {
			DCE dce = new DCE();
			dce.run(module);
		}
		
		return changed;
	}
	
	private boolean optimizeBasicBlock(BasicBlock bb) {
		boolean changed = false;
		List<OptimizationCandidate> optimizationCandidates = new ArrayList<>();
		
		for (Instruction inst : bb.getInstructions()) {
			OptimizationCandidate candidate = findUselessNEComparison(inst);
			if (candidate != null) {
				optimizationCandidates.add(candidate);
				continue;
			}
			
			candidate = findUselessZext(inst);
			if (candidate != null) {
				optimizationCandidates.add(candidate);
			}
		}
		
		for (OptimizationCandidate candidate : optimizationCandidates) {
			applyOptimization(candidate);
			changed = true;
		}
		
		return changed;
	}
	
	private OptimizationCandidate findUselessNEComparison(Instruction inst) {
		if (!(inst instanceof CompareInstruction cmpInst) || 
			!cmpInst.isIntegerCompare() || 
			cmpInst.getPredicate() != OpCode.NE) {
			return null;
		}
		
		Value left = cmpInst.getLeft();
		Value right = cmpInst.getRight();
		
		// 仅匹配与0比较的情况
		boolean rightIsZero = right instanceof ConstantInt constRight && constRight.getValue() == 0;
		boolean leftIsZero = left instanceof ConstantInt constLeft && constLeft.getValue() == 0;
		if (!rightIsZero && !leftIsZero) {
			return null;
		}
		
		Value other = rightIsZero ? left : right;
		
		// 情况1：直接对i1值与0比较，icmp ne i1 %x, 0 => %x
		if (other.getType().equals(IntegerType.I1)) {
			return new OptimizationCandidate(cmpInst, other);
		}
		
		// 情况2：对 zext i1 %x to i32 与0比较，icmp ne i32 (zext i1 %x), 0 => %x
		if (other instanceof ConversionInstruction conv && conv.getConversionType() == OpCode.ZEXT) {
			Value src = conv.getSource();
			if (src.getType().equals(IntegerType.I1)) {
				return new OptimizationCandidate(cmpInst, src);
			}
		}
		
		return null;
	}
	
	private OptimizationCandidate findUselessZext(Instruction inst) {
		if (!(inst instanceof ConversionInstruction convInst) || 
			convInst.getConversionType() != OpCode.ZEXT) {
			return null;
		}
		
		Value source = convInst.getSource();

		// 删除无用户的 zext i1 -> i32 指令
		if (source.getType().equals(IntegerType.I1) && inst.getUsers().isEmpty()) {
			return new OptimizationCandidate(inst, null, OptimizationType.DELETE);
		}
		
		return null;
	}
	
	private void applyOptimization(OptimizationCandidate candidate) {
		if (candidate.type == OptimizationType.SKIP_FOR_NOW) {
			return;
		}
		
		if (candidate.type == OptimizationType.DELETE) {
			Instruction useless = candidate.uselessInstruction;
			if (useless.getUsers().isEmpty()) {
				useless.removeAllOperands();
				useless.removeFromParent();
			}
			return;
		}
		
		Instruction uselessInst = candidate.uselessInstruction;
		Value replacementValue = candidate.replacementValue;
		
		List<User> users = new ArrayList<>(uselessInst.getUsers());
		
		for (User user : users) {
			for (int i = 0; i < user.getOperandCount(); i++) {
				if (user.getOperand(i) == uselessInst) {
					user.setOperand(i, replacementValue);
				}
			}
		}
		
		if (uselessInst.getUsers().isEmpty()) {
			// 先断开该指令与其操作数之间的 Use 链接
			uselessInst.removeAllOperands();
			uselessInst.removeFromParent();
		}
	}
	
	private enum OptimizationType {
		REPLACE,       // 替换指令
		SKIP_FOR_NOW,  // 暂时跳过，可能在后续Pass中处理
		DELETE         // 直接删除死指令
	}
	
	private static class OptimizationCandidate {
		final Instruction uselessInstruction;
		final Value replacementValue;
		final OptimizationType type;
		
		OptimizationCandidate(Instruction uselessInstruction, Value replacementValue) {
			this.uselessInstruction = uselessInstruction;
			this.replacementValue = replacementValue;
			this.type = OptimizationType.REPLACE;
		}
		
		OptimizationCandidate(Instruction uselessInstruction, Value replacementValue, OptimizationType type) {
			this.uselessInstruction = uselessInstruction;
			this.replacementValue = replacementValue;
			this.type = type;
		}
	}
} 