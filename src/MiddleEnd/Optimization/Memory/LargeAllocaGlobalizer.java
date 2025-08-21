package MiddleEnd.Optimization.Memory;

import MiddleEnd.IR.IRBuilder;
import MiddleEnd.IR.Module;
import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.Type.Type;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.*;
import MiddleEnd.Optimization.Core.Optimizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将栈上大数组（alloca array）转换为模块内的全局数组，避免因栈空间不足导致的崩溃。
 */
public class LargeAllocaGlobalizer implements Optimizer.ModuleOptimizer {

	private static final int PROMOTE_THRESHOLD_BYTES = 512 * 1024;

	@Override
	public String getName() {
		return "LargeAllocaGlobalizer";
	}

	@Override
	public boolean run(Module module) {
		boolean changed = false;

		for (Function function : module.functions()) {
			if (function.isExternal()) {
				continue;
			}
			if (isDirectRecursive(function)) {
				continue;
			}

			List<AllocaInstruction> promotableAllocas = new ArrayList<>();

			for (BasicBlock block : function.getBasicBlocks()) {
				for (Instruction inst : block.getInstructions()) {
					if (inst instanceof AllocaInstruction alloca && shouldPromote(alloca)) {
						promotableAllocas.add(alloca);
					}
				}
			}

			for (AllocaInstruction alloca : promotableAllocas) {
				promoteAllocaToGlobal(module, function, alloca);
				changed = true;
			}
		}

		return changed;
	}

	private boolean shouldPromote(AllocaInstruction alloca) {
		if (!alloca.isArrayAllocation()) {
			return false;
		}
		Type elemType = alloca.getAllocatedType();
		int elemSize = elemType.getSize();
		long totalBytes = (long) elemSize * (long) Math.max(alloca.getArraySize(), 0);
		return totalBytes >= PROMOTE_THRESHOLD_BYTES;
	}

	private void promoteAllocaToGlobal(Module module, Function function, AllocaInstruction alloca) {
		String gvName = buildGlobalName(function, alloca);
		Type elementType = alloca.getAllocatedType();
		int arraySize = Math.max(alloca.getArraySize(), 0);

		GlobalVariable globalArray = IRBuilder.createGlobalArray(gvName, elementType, arraySize, module);
		globalArray.setZeroInitialized(arraySize);

		replaceAllUses(alloca, globalArray);

		alloca.removeFromParent();
	}

	private String buildGlobalName(Function function, AllocaInstruction alloca) {
		String funcName = sanitize(function.getName());
		String allocaName = sanitize(alloca.getName());
		return "@promoted_" + funcName + "_" + allocaName;
	}

	private String sanitize(String name) {
		if (name == null) return "unnamed";
		String n = name;
		if (!n.isEmpty() && (n.charAt(0) == '@' || n.charAt(0) == '%')) {
			n = n.substring(1);
		}
		n = n.replace('@', '_').replace('%', '_');
		n = n.replaceAll("[^A-Za-z0-9_\\.]", "_");
		return n.isEmpty() ? "unnamed" : n;
	}

	private void replaceAllUses(Value oldValue, Value newValue) {
		List<User> users = new ArrayList<>(oldValue.getUsers());
		for (User user : users) {
			user.replaceAllUsesWith(oldValue, newValue);
		}
	}

	private boolean isDirectRecursive(Function function) {
		for (BasicBlock bb : function.getBasicBlocks()) {
			for (Instruction inst : bb.getInstructions()) {
				if (inst instanceof CallInstruction call) {
					if (call.getCallee() == function) {
						return true;
					}
				}
			}
		}
		return false;
	}
}