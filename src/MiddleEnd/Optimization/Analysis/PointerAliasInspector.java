package MiddleEnd.Optimization.Analysis;

import MiddleEnd.IR.Type.IntegerType;
import MiddleEnd.IR.Type.PointerType;
import MiddleEnd.IR.Value.*;
import MiddleEnd.IR.Value.Instructions.AllocaInstruction;
import MiddleEnd.IR.Value.Instructions.PhiInstruction;
import MiddleEnd.IR.Value.Instructions.GetElementPtrInstruction;
import MiddleEnd.IR.Value.Instructions.LoadInstruction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Stack;

/**
 * 指针别名分析器
 * 用于分析指针之间的别名关系
 */
public class PointerAliasInspector {

    public enum AliasResult {
        NO,     // 确定不别名
        MAY,    // 可能别名
        YES     // 确定别名
    }

    /**
     * 获取某个指针value的根定义
     * 要么是alloc指令(局部变量，局部数组或参数数组)，要么是全局变量
     */
    public static Value getRoot(Value pointer) {
        Value iter = pointer;

        // 这里loadInst是因为有可能为局部变量或参数数组(本质上最初定义也是allocInst)
        // 我们一路循环直到iter是AllocInst或者为全局变量
        while (iter instanceof GetElementPtrInstruction || iter instanceof LoadInstruction) {
            if (iter instanceof GetElementPtrInstruction) {
                iter = ((GetElementPtrInstruction) iter).getPointer();
            } else {
                iter = ((LoadInstruction) iter).getPointer();
            }
        }

        return iter;
    }

    public static boolean isGlobal(Value array) {
        return array instanceof GlobalVariable;
    }

    public static boolean isParam(Value array) {
        if (array instanceof Argument argument) {
            return argument.getType().isPointerType();
        }
        return false;
    }

    public static boolean isLocal(Value array) {
        return !isGlobal(array) && !isParam(array);
    }

    /**
     * 检查是否为静态地址空间（编译时可确定的地址）
     */
    public static boolean isStaticSpace(Value root, LinkedHashSet<Value> adding) {
        if (!(root.getType().isPointerType())) {
            return false;
        }
        if (root instanceof Argument) {
            return false;
        }
        for (var val : adding) {
            if (!(val instanceof ConstantInt)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isStaticSpace(Value ptr) {
        return isStaticSpace(getRoot(ptr), dumpAddingListFromPtr(ptr));
    }

    /**
     * 简化偏移列表，将所有常量偏移合并
     */
    private static void simplifyAddingList(LinkedHashSet<Value> adding) {
        int allConstSum = 0;
        ArrayList<ConstantInt> needRemove = new ArrayList<>();
        for (var val : adding) {
            if (val instanceof ConstantInt constVal) {
                allConstSum += constVal.getValue();
                needRemove.add(constVal);
            }
        }
        for (var removeVal : needRemove) {
            adding.remove(removeVal);
        }
        adding.add(new ConstantInt(allConstSum, IntegerType.I32));
    }

    /**
     * 从偏移列表中获取任意一个常量
     */
    private static ConstantInt getAnyConstFromHashList(LinkedHashSet<Value> adding) {
        for (var val : adding) {
            if (val instanceof ConstantInt constVal) {
                return constVal;
            }
        }
        return null;
    }

    /**
     * 比较两个偏移列表是否相同
     */
    private static boolean isSameAddingList(LinkedHashSet<Value> adding1, LinkedHashSet<Value> adding2) {
        var const1 = getAnyConstFromHashList(adding1);
        var const2 = getAnyConstFromHashList(adding2);
        if (const1 == null || const2 == null || const1.getValue() != const2.getValue()) {
            return false;
        }
        for (var val : adding1) {
            if (val instanceof ConstantInt) {
                continue;
            }
            if (!adding2.contains(val)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从指针中提取偏移列表
     */
    public static LinkedHashSet<Value> dumpAddingListFromPtr(Value value) {
        Value root;
        LinkedHashSet<Value> adding = new LinkedHashSet<>();
        if (value instanceof GetElementPtrInstruction) {
            root = getRoot(value);
            var tmp = value;
            while (tmp != root) {
                assert tmp instanceof GetElementPtrInstruction;
                // 获取GEP指令的索引
                GetElementPtrInstruction gepInst = (GetElementPtrInstruction) tmp;
                for (int i = 1; i < gepInst.getOperands().size(); i++) {
                    adding.add(gepInst.getOperands().get(i));
                }
                tmp = gepInst.getPointer();
            }
        } else {
            root = value;
            adding.add(new ConstantInt(0, IntegerType.I32));
        }
        simplifyAddingList(adding);
        return adding;
    }

    /**
     * 检查两个指针是否可能别名
     * 要求传入指针类型。如果不是指针类型，则总是返回MAY
     */
    public static AliasResult checkAlias(Value value1, Value value2) {
        if (!(value1.getType() instanceof PointerType) || !(value2.getType() instanceof PointerType)) {
            return AliasResult.MAY;
        }
        
        Value root1 = getRoot(value1), root2 = getRoot(value2);
        if (root1 instanceof PhiInstruction || root2 instanceof PhiInstruction) {
            return AliasResult.MAY;
        }
        
        var adding1 = dumpAddingListFromPtr(value1);
        var adding2 = dumpAddingListFromPtr(value2);

        // value = root(alloca/Arg/Global) + addinglist
        if (!root1.equals(root2)) {
            return AliasResult.NO;
        }
        
        // same root
        // Argument, Alloca, Global may be root
        // Argument, Alloca, Global, GEP may be value
        if (isStaticSpace(root1, adding1) && isStaticSpace(root2, adding2)) {
            // 同一根指针，地址可在编译期确定
            if (adding1.size() > 1 || adding2.size() > 1) {
                // 复杂偏移，只有偏移完全一致时才能确定别名
                if (isSameAddingList(adding1, adding2)) {
                    return AliasResult.YES;
                }
                // 偏移不同，可能别名也可能不别名，返回 MAY 保守处理
                return AliasResult.MAY;
            } else {
                // 两者都是单常量偏移
                assert adding1.size() == 1 && adding2.size() == 1;
                int offset1 = getAnyConstFromHashList(adding1).getValue();
                int offset2 = getAnyConstFromHashList(adding2).getValue();
                if (offset1 == offset2) {
                    // 完全相同地址
                    return AliasResult.YES;
                }
                // 如果其中一个偏移为0（基址），则无法确定不重叠，返回 MAY
                if (offset1 == 0 || offset2 == 0) {
                    return AliasResult.MAY;
                }
                // 两个不同且非0的常量偏移代表不同元素，认为不别名
                return AliasResult.NO;
            }
        }
        return AliasResult.MAY;
    }

    /**
     * 检查一个值是否与Phi指令相关
     */
    public static boolean isPhiRelated(Value value) {
        if (!(value instanceof User)) {
            return false;
        }
        Stack<Value> checklist = new Stack<>();
        checklist.add(value);
        while (!checklist.isEmpty()) {
            var check = checklist.pop();
            if (check instanceof PhiInstruction) {
                return true;
            }
            if (check instanceof User) {
                checklist.addAll(((User) check).getOperands());
            }
        }
        return false;
    }
} 