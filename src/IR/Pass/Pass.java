package IR.Pass;

import IR.Module;

/**
 * IR优化Pass的基础接口
 */
public interface Pass {
    /**
     * 获取Pass的名称
     */
    String getName();
    
    /**
     * IR优化Pass接口
     */
    interface IRPass extends Pass {
        /**
         * 执行IR优化
         */
        void run(Module module);
    }
    
    /**
     * 函数Pass接口，只对单个函数进行优化
     */
    interface FunctionPass extends Pass {
        /**
         * 对单个函数执行优化
         */
        void runOnFunction(IR.Value.Function function);
    }
} 