package IR.Pass;

import IR.Module;
import IR.Value.Function;

/**
 * IR优化Pass接口
 */
public interface Pass {
    /**
     * 获取Pass的名称
     */
    String getName();
    
    /**
     * 模块级Pass接口，处理整个IR模块
     */
    interface IRPass extends Pass {
        /**
         * 运行Pass
         * @param module IR模块
         * @return 如果IR发生变化返回true，否则返回false
         */
        boolean run(Module module);
    }
    
    /**
     * 函数级Pass接口，处理单个函数
     */
    interface FunctionPass extends Pass {
        /**
         * 运行Pass
         * @param function 目标函数
         * @return 如果IR发生变化返回true，否则返回false
         */
        boolean run(Function function);
    }
    
    /**
     * 分析Pass接口，不修改IR，只进行分析
     */
    interface AnalysisPass extends Pass {
        /**
         * 运行分析
         * @param module IR模块
         */
        void run(Module module);
        
        /**
         * 获取分析结果
         * @return 分析结果
         */
        Object getResult();
    }
} 