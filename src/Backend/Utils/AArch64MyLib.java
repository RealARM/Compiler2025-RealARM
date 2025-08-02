package Backend.Utils;

import java.util.HashMap;
import java.util.Map;

public class AArch64MyLib {
    
    private static final Map<String, String> FUNCTION_64BIT_MAPPING = new HashMap<>();
    
    static {
        FUNCTION_64BIT_MAPPING.put("getarray", "getarray64");
        FUNCTION_64BIT_MAPPING.put("putarray", "putarray64");
        FUNCTION_64BIT_MAPPING.put("getfarray", "getfarray64");
        FUNCTION_64BIT_MAPPING.put("putfarray", "putfarray64");
        FUNCTION_64BIT_MAPPING.put("memset", "memset");
    }

    private static final Map<String, Boolean> FUNCTION_64BIT_USAGE = new HashMap<>();
    
    static {
        FUNCTION_64BIT_USAGE.put("getarray", false);
        FUNCTION_64BIT_USAGE.put("putarray", false);
        FUNCTION_64BIT_USAGE.put("getfarray", false);
        FUNCTION_64BIT_USAGE.put("putfarray", false);
        FUNCTION_64BIT_USAGE.put("memset", false);
    }
    
    public static String get64BitFunctionName(String functionName) {
        return FUNCTION_64BIT_MAPPING.getOrDefault(functionName, functionName);
    }
    
    public static void markFunction64Used(String functionName) {
        if (FUNCTION_64BIT_USAGE.containsKey(functionName)) {
            FUNCTION_64BIT_USAGE.put(functionName, true);
        }
    }
    
    public static boolean has64BitVersion(String functionName) {
        return FUNCTION_64BIT_MAPPING.containsKey(functionName);
    }
    
    public static java.util.Set<String> getSupportedFunctions() {
        return FUNCTION_64BIT_MAPPING.keySet();
    }
    
    public static String generateMemsetFunction() {
        if (FUNCTION_64BIT_USAGE.get("memset") == false) {
            return "\n";
        }
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

    public static String generateGetarray64Function() {
        if (FUNCTION_64BIT_USAGE.get("getarray") == false) {
            return "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(".global getarray64\n");
        sb.append("getarray64:\n");
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        sb.append("\tmov x29, sp\n");
        sb.append("\tsub sp, sp, #288\n");
        sb.append("getarray64_block0:\n");
        sb.append("\tstr\tx19,	[sp, #8]\n");
        sb.append("\tstr\tx20,	[sp, #16]\n");
        sb.append("\tstr\tx21,	[sp, #24]\n");
        sb.append("\tstr\tx22,	[sp, #32]\n");
        sb.append("\tstr\tx23,	[sp, #40]\n");
        sb.append("\tstr\tx24,	[sp, #48]\n");
        sb.append("\tstr\tx25,	[sp, #56]\n");
        sb.append("\tstr\tx26,	[sp, #64]\n");
        sb.append("\tstr d8, [sp, #72]\n");
        sb.append("\tstr d9, [sp, #80]\n");
        sb.append("\tstr d10, [sp, #88]\n");
        sb.append("\tstr d11, [sp, #96]\n");
        sb.append("\tstr d12, [sp, #104]\n");
        sb.append("\tstr d13, [sp, #112]\n");
        sb.append("\tstr d14, [sp, #120]\n");
        sb.append("\tstr d15, [sp, #128]\n");
        sb.append("\tstr d16, [sp, #136]\n");
        sb.append("\tstr d17, [sp, #144]\n");
        sb.append("\tstr d18, [sp, #152]\n");
        sb.append("\tstr d19, [sp, #160]\n");
        sb.append("\tstr d20, [sp, #168]\n");
        sb.append("\tstr d21, [sp, #176]\n");
        sb.append("\tstr d22, [sp, #184]\n");
        sb.append("\tstr d23, [sp, #192]\n");
        sb.append("\tstr d24, [sp, #200]\n");
        sb.append("\tstr d25, [sp, #208]\n");
        sb.append("\tstr d26, [sp, #216]\n");
        sb.append("\tstr d27, [sp, #224]\n");
        sb.append("\tstr d28, [sp, #232]\n");
        sb.append("\tstr d29, [sp, #240]\n");
        sb.append("\tstr d30, [sp, #248]\n");
        sb.append("\tstr d31, [sp, #256]\n");
        sb.append("\tadd x8, sp, #264\n");
        sb.append("\tmov x8, #0\n");
        sb.append("\tstr x8, [sp, #264]\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tbl getint\n");
        sb.append("\tmov x8, x0\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tstr x8, [sp, #264]\n");
        sb.append("\tadd x8, sp, #272\n");
        sb.append("\tmov x8, #0\n");
        sb.append("\tstr x8, [sp, #272]\n");
        sb.append("\tb getarray64_block1\n");
        sb.append("getarray64_block1:\n");
        sb.append("\tldr x9, [sp, #272]\n");
        sb.append("\tldr x8, [sp, #264]\n");
        sb.append("\tcmp x9, x8\n");
        sb.append("\tcset x8, lt\n");
        sb.append("\tcmp x8, #0\n");
        sb.append("\tcset x9, ne\n");
        sb.append("\tcmp x8, #0\n");
        sb.append("\tbne getarray64_block2\n");
        sb.append("\tb getarray64_block3\n");
        sb.append("getarray64_block2:\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tbl getint\n");
        sb.append("\tmov x10, x0\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tldr x8, [sp, #272]\n");
        sb.append("\tmov x9, x0\n");
        sb.append("\tlsl x8, x8, #3\n");
        sb.append("\tadd x9, x9, x8\n");
        //符号扩展思密达
        sb.append("\tsxtw x10, w10\n");
        //扩展完再给我存
        sb.append("\tstr x10, [x9]\n");
        sb.append("\tldr x8, [sp, #272]\n");
        sb.append("\tadd x8, x8, #1\n");
        sb.append("\tstr x8, [sp, #272]\n");
        sb.append("\tb getarray64_block1\n");
        sb.append("getarray64_block3:\n");
        sb.append("\tldr x8, [sp, #264]\n");
        sb.append("\tmov x0, x8\n");
        sb.append("\tldr x19, [sp, #8]\n");
        sb.append("\tldr x20, [sp, #16]\n");
        sb.append("\tldr x21, [sp, #24]\n");
        sb.append("\tldr x22, [sp, #32]\n");
        sb.append("\tldr x23, [sp, #40]\n");
        sb.append("\tldr x24, [sp, #48]\n");
        sb.append("\tldr x25, [sp, #56]\n");
        sb.append("\tldr x26, [sp, #64]\n");
        sb.append("\tldr d8, [sp, #72]\n");
        sb.append("\tldr d9, [sp, #80]\n");
        sb.append("\tldr d10, [sp, #88]\n");
        sb.append("\tldr d11, [sp, #96]\n");
        sb.append("\tldr d12, [sp, #104]\n");
        sb.append("\tldr d13, [sp, #112]\n");
        sb.append("\tldr d14, [sp, #120]\n");
        sb.append("\tldr d15, [sp, #128]\n");
        sb.append("\tldr d16, [sp, #136]\n");
        sb.append("\tldr d17, [sp, #144]\n");
        sb.append("\tldr d18, [sp, #152]\n");
        sb.append("\tldr d19, [sp, #160]\n");
        sb.append("\tldr d20, [sp, #168]\n");
        sb.append("\tldr d21, [sp, #176]\n");
        sb.append("\tldr d22, [sp, #184]\n");
        sb.append("\tldr d23, [sp, #192]\n");
        sb.append("\tldr d24, [sp, #200]\n");
        sb.append("\tldr d25, [sp, #208]\n");
        sb.append("\tldr d26, [sp, #216]\n");
        sb.append("\tldr d27, [sp, #224]\n");
        sb.append("\tldr d28, [sp, #232]\n");
        sb.append("\tldr d29, [sp, #240]\n");
        sb.append("\tldr d30, [sp, #248]\n");
        sb.append("\tldr d31, [sp, #256]\n");
        sb.append("\tadd sp, sp, #288\n");
        sb.append("\tldp x29, x30, [sp], #16\n");
        sb.append("\tret\n");
        return sb.toString();
    }
    
    public static String generatePutarray64Function() {
        if (FUNCTION_64BIT_USAGE.get("putarray") == false) {
            return "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(".global putarray64\n");
        sb.append("putarray64:\n");
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        sb.append("\tmov x29, sp\n");
        sb.append("\tsub sp, sp, #288\n");
        sb.append("putarray64_block0:\n");
        sb.append("\tstr\tx19,	[sp, #16]\n");
        sb.append("\tstr\tx20,	[sp, #24]\n");
        sb.append("\tstr\tx21,	[sp, #32]\n");
        sb.append("\tstr\tx22,	[sp, #40]\n");
        sb.append("\tstr\tx23,	[sp, #48]\n");
        sb.append("\tstr\tx24,	[sp, #56]\n");
        sb.append("\tstr\tx25,	[sp, #64]\n");
        sb.append("\tstr\tx26,	[sp, #72]\n");
        sb.append("\tstr\td8,	[sp, #80]\n");
        sb.append("\tstr\td9,	[sp, #88]\n");
        sb.append("\tstr\td10,	[sp, #96]\n");
        sb.append("\tstr\td11,	[sp, #104]\n");
        sb.append("\tstr\td12,	[sp, #112]\n");
        sb.append("\tstr\td13,	[sp, #120]\n");
        sb.append("\tstr\td14,	[sp, #128]\n");
        sb.append("\tstr\td15,	[sp, #136]\n");
        sb.append("\tstr\td16,	[sp, #144]\n");
        sb.append("\tstr\td17,	[sp, #152]\n");
        sb.append("\tstr\td18,	[sp, #160]\n");
        sb.append("\tstr\td19,	[sp, #168]\n");
        sb.append("\tstr\td20,	[sp, #176]\n");
        sb.append("\tstr\td21,	[sp, #184]\n");
        sb.append("\tstr\td22,	[sp, #192]\n");
        sb.append("\tstr\td23,	[sp, #200]\n");
        sb.append("\tstr\td24,	[sp, #208]\n");
        sb.append("\tstr\td25,	[sp, #216]\n");
        sb.append("\tstr\td26,	[sp, #224]\n");
        sb.append("\tstr\td27,	[sp, #232]\n");
        sb.append("\tstr\td28,	[sp, #240]\n");
        sb.append("\tstr\td29,	[sp, #248]\n");
        sb.append("\tstr\td30,	[sp, #256]\n");
        sb.append("\tstr\td31,	[sp, #264]\n");
        sb.append("\tadd\tx8, sp, #272\n");
        sb.append("\tstr\tx0,	[sp, #272]\n");
        sb.append("\tstr\tx0,	[sp]\n");
        sb.append("\tstr\tx1,	[sp, #8]\n");
        sb.append("\tbl\tputint\n");
        sb.append("\tldr\tx0,	[sp]\n");
        sb.append("\tldr\tx1,	[sp, #8]\n");
        sb.append("\tstr\tx0,	[sp]\n");
        sb.append("\tstr\tx1,	[sp, #8]\n");
        sb.append("\tmov\tx0,	#58\n");
        sb.append("\tbl\tputch\n");
        sb.append("\tldr\tx0,	[sp]\n");
        sb.append("\tldr\tx1,	[sp, #8]\n");
        sb.append("\tadd\tx8, sp, #280\n");
        sb.append("\tmov\tx8,	#0\n");
        sb.append("\tstr\tx8,	[sp, #280]\n");
        sb.append("\tb\tputarray64_block1\n");
        sb.append("putarray64_block1:\n");
        sb.append("\tldr\tx9,	[sp, #280]\n");
        sb.append("\tldr\tx8,	[sp, #272]\n");
        sb.append("\tcmp\tx9,	x8\n");
        sb.append("\tcset x8, lt\n");
        sb.append("\tcmp x8,	#0\n");
        sb.append("\tcset x9, ne\n");
        sb.append("\tcmp x8,	#0\n");
        sb.append("\tbne\tputarray64_block2\n");
        sb.append("\tb\tputarray64_block3\n");
        sb.append("putarray64_block2:\n");
        sb.append("\tstr\tx0,	[sp]\n");
        sb.append("\tstr\tx1,	[sp, #8]\n");
        sb.append("\tmov\tx0,	#32\n");
        sb.append("\tbl\tputch\n");
        sb.append("\tldr\tx0,	[sp]\n");
        sb.append("\tldr\tx1,	[sp, #8]\n");
        sb.append("\tldr\tx8,	[sp, #280]\n");
        sb.append("\tmov\tx9,	x1\n");
        sb.append("\tlsl\tx8, x8, #3\n");
        sb.append("\tadd\tx9, x9, x8\n");
        sb.append("\tldr\tx8,	[x9]\n");
        sb.append("\tstr\tx0,	[sp]\n");
        sb.append("\tstr\tx1,	[sp, #8]\n");
        sb.append("\tmov\tx0,	x8\n");
        sb.append("\tbl\tputint\n");
        sb.append("\tldr\tx0,	[sp]\n");
        sb.append("\tldr\tx1,	[sp, #8]\n");
        sb.append("\tldr\tx8,	[sp, #280]\n");
        sb.append("\tadd\tx8, x8, #1\n");
        sb.append("\tstr\tx8,	[sp, #280]\n");
        sb.append("\tb\tputarray64_block1\n");
        sb.append("putarray64_block3:\n");
        sb.append("\tstr\tx0,	[sp]\n");
        sb.append("\tstr\tx1,	[sp, #8]\n");
        sb.append("\tmov\tx0,	#10\n");
        sb.append("\tbl\tputch\n");
        sb.append("\tldr\tx0,	[sp]\n");
        sb.append("\tldr\tx1,	[sp, #8]\n");
        sb.append("\tldr x19, [sp, #16]\n");
        sb.append("\tldr x20, [sp, #24]\n");
        sb.append("\tldr x21, [sp, #32]\n");
        sb.append("\tldr x22, [sp, #40]\n");
        sb.append("\tldr x23, [sp, #48]\n");
        sb.append("\tldr x24, [sp, #56]\n");
        sb.append("\tldr x25, [sp, #64]\n");
        sb.append("\tldr x26, [sp, #72]\n");
        sb.append("\tldr\td8,	[sp, #80]\n");
        sb.append("\tldr\td9,	[sp, #88]\n");
        sb.append("\tldr\td10,	[sp, #96]\n");
        sb.append("\tldr\td11,	[sp, #104]\n");
        sb.append("\tldr\td12,	[sp, #112]\n");
        sb.append("\tldr\td13,	[sp, #120]\n");
        sb.append("\tldr\td14,	[sp, #128]\n");
        sb.append("\tldr\td15,	[sp, #136]\n");
        sb.append("\tldr\td16,	[sp, #144]\n");
        sb.append("\tldr\td17,	[sp, #152]\n");
        sb.append("\tldr\td18,	[sp, #160]\n");
        sb.append("\tldr\td19,	[sp, #168]\n");
        sb.append("\tldr\td20,	[sp, #176]\n");
        sb.append("\tldr\td21,	[sp, #184]\n");
        sb.append("\tldr\td22,	[sp, #192]\n");
        sb.append("\tldr\td23,	[sp, #200]\n");
        sb.append("\tldr\td24,	[sp, #208]\n");
        sb.append("\tldr\td25,	[sp, #216]\n");
        sb.append("\tldr\td26,	[sp, #224]\n");
        sb.append("\tldr\td27,	[sp, #232]\n");
        sb.append("\tldr\td28,	[sp, #240]\n");
        sb.append("\tldr\td29,	[sp, #248]\n");
        sb.append("\tldr\td30,	[sp, #256]\n");
        sb.append("\tldr\td31,	[sp, #264]\n");
        sb.append("\tadd\tsp, sp, #288\n");
        sb.append("\tldp\tx29, x30, [sp], #16\n");
        sb.append("\tret\n");
        return sb.toString();
    }
    
    public static String generateGetfarray64Function() {
        if (FUNCTION_64BIT_USAGE.get("getfarray") == false) {
            return "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(".global getfarray64\n");
        sb.append("getfarray64:\n");
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        sb.append("\tmov x29, sp\n");
        sb.append("\tsub sp, sp, #288\n");
        sb.append("getfarray64_block0:\n");
        sb.append("\tstr\tx19,	[sp, #8]\n");
        sb.append("\tstr\tx20,	[sp, #16]\n");
        sb.append("\tstr\tx21,	[sp, #24]\n");
        sb.append("\tstr\tx22,	[sp, #32]\n");
        sb.append("\tstr\tx23,	[sp, #40]\n");
        sb.append("\tstr\tx24,	[sp, #48]\n");
        sb.append("\tstr\tx25,	[sp, #56]\n");
        sb.append("\tstr\tx26,	[sp, #64]\n");
        sb.append("\tstr d8, [sp, #72]\n");
        sb.append("\tstr d9, [sp, #80]\n");
        sb.append("\tstr d10, [sp, #88]\n");
        sb.append("\tstr d11, [sp, #96]\n");
        sb.append("\tstr d12, [sp, #104]\n");
        sb.append("\tstr d13, [sp, #112]\n");
        sb.append("\tstr d14, [sp, #120]\n");
        sb.append("\tstr d15, [sp, #128]\n");
        sb.append("\tstr d16, [sp, #136]\n");
        sb.append("\tstr d17, [sp, #144]\n");
        sb.append("\tstr d18, [sp, #152]\n");
        sb.append("\tstr d19, [sp, #160]\n");
        sb.append("\tstr d20, [sp, #168]\n");
        sb.append("\tstr d21, [sp, #176]\n");
        sb.append("\tstr d22, [sp, #184]\n");
        sb.append("\tstr d23, [sp, #192]\n");
        sb.append("\tstr d24, [sp, #200]\n");
        sb.append("\tstr d25, [sp, #208]\n");
        sb.append("\tstr d26, [sp, #216]\n");
        sb.append("\tstr d27, [sp, #224]\n");
        sb.append("\tstr d28, [sp, #232]\n");
        sb.append("\tstr d29, [sp, #240]\n");
        sb.append("\tstr d30, [sp, #248]\n");
        sb.append("\tstr d31, [sp, #256]\n");
        sb.append("\tadd x8, sp, #264\n");
        sb.append("\tmov x8, #0\n");
        sb.append("\tstr x8, [sp, #264]\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tbl getint\n");
        sb.append("\tmov x8, x0\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tstr x8, [sp, #264]\n");
        sb.append("\tadd x8, sp, #272\n");
        sb.append("\tmov x8, #0\n");
        sb.append("\tstr x8, [sp, #272]\n");
        sb.append("\tb getfarray64_block1\n");
        sb.append("getfarray64_block1:\n");
        sb.append("\tldr x9, [sp, #272]\n");
        sb.append("\tldr x8, [sp, #264]\n");
        sb.append("\tcmp x9, x8\n");
        sb.append("\tcset x8, lt\n");
        sb.append("\tcmp x8, #0\n");
        sb.append("\tcset x9, ne\n");
        sb.append("\tcmp x8, #0\n");
        sb.append("\tbne getfarray64_block2\n");
        sb.append("\tb getfarray64_block3\n");
        sb.append("getfarray64_block2:\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tbl getfloat\n");
        sb.append("\tfmov d8, d0\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tldr x8, [sp, #272]\n");
        sb.append("\tmov x9, x0\n");
        sb.append("\tlsl x8, x8, #3\n");
        sb.append("\tadd x9, x9, x8\n");
        //来吧，类型转换
        sb.append("\tfcvt d8, s8\n");
        //跟你拼了
        sb.append("\tstr d8, [x9]\n");
        sb.append("\tldr x8, [sp, #272]\n");
        sb.append("\tadd x8, x8, #1\n");
        sb.append("\tstr x8, [sp, #272]\n");
        sb.append("\tb getfarray64_block1\n");
        sb.append("getfarray64_block3:\n");
        sb.append("\tldr x8, [sp, #264]\n");
        sb.append("\tmov x0, x8\n");
        sb.append("\tldr x19, [sp, #8]\n");
        sb.append("\tldr x20, [sp, #16]\n");
        sb.append("\tldr x21, [sp, #24]\n");
        sb.append("\tldr x22, [sp, #32]\n");
        sb.append("\tldr x23, [sp, #40]\n");
        sb.append("\tldr x24, [sp, #48]\n");
        sb.append("\tldr x25, [sp, #56]\n");
        sb.append("\tldr x26, [sp, #64]\n");
        sb.append("\tldr d8, [sp, #72]\n");
        sb.append("\tldr d9, [sp, #80]\n");
        sb.append("\tldr d10, [sp, #88]\n");
        sb.append("\tldr d11, [sp, #96]\n");
        sb.append("\tldr d12, [sp, #104]\n");
        sb.append("\tldr d13, [sp, #112]\n");
        sb.append("\tldr d14, [sp, #120]\n");
        sb.append("\tldr d15, [sp, #128]\n");
        sb.append("\tldr d16, [sp, #136]\n");
        sb.append("\tldr d17, [sp, #144]\n");
        sb.append("\tldr d18, [sp, #152]\n");
        sb.append("\tldr d19, [sp, #160]\n");
        sb.append("\tldr d20, [sp, #168]\n");
        sb.append("\tldr d21, [sp, #176]\n");
        sb.append("\tldr d22, [sp, #184]\n");
        sb.append("\tldr d23, [sp, #192]\n");
        sb.append("\tldr d24, [sp, #200]\n");
        sb.append("\tldr d25, [sp, #208]\n");
        sb.append("\tldr d26, [sp, #216]\n");
        sb.append("\tldr d27, [sp, #224]\n");
        sb.append("\tldr d28, [sp, #232]\n");
        sb.append("\tldr d29, [sp, #240]\n");
        sb.append("\tldr d30, [sp, #248]\n");
        sb.append("\tldr d31, [sp, #256]\n");
        sb.append("\tadd sp, sp, #288\n");
        sb.append("\tldp x29, x30, [sp], #16\n");
        sb.append("\tret\n");
        return sb.toString();
    }
    
    public static String generatePutfarray64Function() {
        if (FUNCTION_64BIT_USAGE.get("putfarray") == false) {
            return "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(".global putfarray64\n");
        sb.append("putfarray64:\n");
        sb.append("\tstp x29, x30, [sp, #-16]!\n");
        sb.append("\tmov x29, sp\n");
        sb.append("\tsub sp, sp, #288\n");
        sb.append("putfarray64_block0:\n");
        sb.append("\tstr\tx19,	[sp, #16]\n");
        sb.append("\tstr\tx20,	[sp, #24]\n");
        sb.append("\tstr\tx21,	[sp, #32]\n");
        sb.append("\tstr\tx22,	[sp, #40]\n");
        sb.append("\tstr\tx23,	[sp, #48]\n");
        sb.append("\tstr\tx24,	[sp, #56]\n");
        sb.append("\tstr\tx25,	[sp, #64]\n");
        sb.append("\tstr\tx26,	[sp, #72]\n");
        sb.append("\tstr d8, [sp, #80]\n");
        sb.append("\tstr d9, [sp, #88]\n");
        sb.append("\tstr d10, [sp, #96]\n");
        sb.append("\tstr d11, [sp, #104]\n");
        sb.append("\tstr d12, [sp, #112]\n");
        sb.append("\tstr d13, [sp, #120]\n");
        sb.append("\tstr d14, [sp, #128]\n");
        sb.append("\tstr d15, [sp, #136]\n");
        sb.append("\tstr d16, [sp, #144]\n");
        sb.append("\tstr d17, [sp, #152]\n");
        sb.append("\tstr d18, [sp, #160]\n");
        sb.append("\tstr d19, [sp, #168]\n");
        sb.append("\tstr d20, [sp, #176]\n");
        sb.append("\tstr d21, [sp, #184]\n");
        sb.append("\tstr d22, [sp, #192]\n");
        sb.append("\tstr d23, [sp, #200]\n");
        sb.append("\tstr d24, [sp, #208]\n");
        sb.append("\tstr d25, [sp, #216]\n");
        sb.append("\tstr d26, [sp, #224]\n");
        sb.append("\tstr d27, [sp, #232]\n");
        sb.append("\tstr d28, [sp, #240]\n");
        sb.append("\tstr d29, [sp, #248]\n");
        sb.append("\tstr d30, [sp, #256]\n");
        sb.append("\tstr d31, [sp, #264]\n");
        sb.append("\tadd x8, sp, #272\n");
        sb.append("\tstr x0, [sp, #272]\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tstr x1, [sp, #8]\n");
        sb.append("\tbl putint\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tldr x1, [sp, #8]\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tstr x1, [sp, #8]\n");
        sb.append("\tmov x0, #58\n");
        sb.append("\tbl putch\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tldr x1, [sp, #8]\n");
        sb.append("\tadd x8, sp, #280\n");
        sb.append("\tmov x8, #0\n");
        sb.append("\tstr x8, [sp, #280]\n");
        sb.append("\tb putfarray64_block1\n");
        sb.append("putfarray64_block1:\n");
        sb.append("\tldr x9, [sp, #280]\n");
        sb.append("\tldr x8, [sp, #272]\n");
        sb.append("\tcmp x9, x8\n");
        sb.append("\tcset x8, lt\n");
        sb.append("\tcmp x8, #0\n");
        sb.append("\tcset x9, ne\n");
        sb.append("\tcmp x8, #0\n");
        sb.append("\tbne putfarray64_block2\n");
        sb.append("\tb putfarray64_block3\n");
        sb.append("putfarray64_block2:\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tstr x1, [sp, #8]\n");
        sb.append("\tmov x0, #32\n");
        sb.append("\tbl putch\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tldr x1, [sp, #8]\n");
        sb.append("\tldr x8, [sp, #280]\n");
        sb.append("\tmov x9, x1\n");
        sb.append("\tlsl x8, x8, #3\n");
        sb.append("\tadd x9, x9, x8\n");
        sb.append("\tldr d8, [x9]\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tstr x1, [sp, #8]\n");
        sb.append("\tfmov d0, d8\n");
        //卡密一手
        sb.append("\tfcvt s0, d0\n");
        sb.append("\tbl putfloat\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tldr x1, [sp, #8]\n");
        sb.append("\tldr x8, [sp, #280]\n");
        sb.append("\tadd x8, x8, #1\n");
        sb.append("\tstr x8, [sp, #280]\n");
        sb.append("\tb putfarray64_block1\n");
        sb.append("putfarray64_block3:\n");
        sb.append("\tstr x0, [sp]\n");
        sb.append("\tstr x1, [sp, #8]\n");
        sb.append("\tmov x0, #10\n");
        sb.append("\tbl putch\n");
        sb.append("\tldr x0, [sp]\n");
        sb.append("\tldr x1, [sp, #8]\n");
        sb.append("\tldr x19, [sp, #16]\n");
        sb.append("\tldr x20, [sp, #24]\n");
        sb.append("\tldr x21, [sp, #32]\n");
        sb.append("\tldr x22, [sp, #40]\n");
        sb.append("\tldr x23, [sp, #48]\n");
        sb.append("\tldr x24, [sp, #56]\n");
        sb.append("\tldr x25, [sp, #64]\n");
        sb.append("\tldr x26, [sp, #72]\n");
        sb.append("\tldr d8, [sp, #80]\n");
        sb.append("\tldr d9, [sp, #88]\n");
        sb.append("\tldr d10, [sp, #96]\n");
        sb.append("\tldr d11, [sp, #104]\n");
        sb.append("\tldr d12, [sp, #112]\n");
        sb.append("\tldr d13, [sp, #120]\n");
        sb.append("\tldr d14, [sp, #128]\n");
        sb.append("\tldr d15, [sp, #136]\n");
        sb.append("\tldr d16, [sp, #144]\n");
        sb.append("\tldr d17, [sp, #152]\n");
        sb.append("\tldr d18, [sp, #160]\n");
        sb.append("\tldr d19, [sp, #168]\n");
        sb.append("\tldr d20, [sp, #176]\n");
        sb.append("\tldr d21, [sp, #184]\n");
        sb.append("\tldr d22, [sp, #192]\n");
        sb.append("\tldr d23, [sp, #200]\n");
        sb.append("\tldr d24, [sp, #208]\n");
        sb.append("\tldr d25, [sp, #216]\n");
        sb.append("\tldr d26, [sp, #224]\n");
        sb.append("\tldr d27, [sp, #232]\n");
        sb.append("\tldr d28, [sp, #240]\n");
        sb.append("\tldr d29, [sp, #248]\n");
        sb.append("\tldr d30, [sp, #256]\n");
        sb.append("\tldr d31, [sp, #264]\n");
        sb.append("\tadd sp, sp, #288\n");
        sb.append("\tldp x29, x30, [sp], #16\n");
        sb.append("\tret\n");
        return sb.toString();
    }
    
}
