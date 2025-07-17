#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import subprocess
import sys
import json
import difflib
from pathlib import Path
import tempfile
import time
import shutil
import re

# 配置信息
TESTS_DIR = "./tests"
# 参考jar包路径
REF_JAR_PATH = "TestLexer.jar"  # 默认使用TestLexer.jar
# 当前项目的源代码和输出目录
PROJECT_SRC = "./src"
PROJECT_OUT = "./out"
CLASS_PATH = f"{PROJECT_OUT}/production"
# 调试模式
DEBUG = False
# 测试模式: 'both', 'lexer', 'parser'
TEST_MODE = 'both'
# 保存失败输出的目录
FAILED_DIR = "./failed_outputs"

# Token 类型映射(参考实现 <-> 当前实现 -> 统一名称)
TOKEN_MAP = {
    # 标识符 & 常量
    "IDENFR": "IDENTIFIER", "IDENTIFIER": "IDENTIFIER",
    "HEXCON": "HEX_CONST", "HEX_CONST": "HEX_CONST",
    "OCTCON": "OCT_CONST", "OCT_CONST": "OCT_CONST",
    "DECCON": "DEC_CONST", "DEC_CONST": "DEC_CONST",
    "HEXFCON": "HEX_FLOAT", "HEX_FLOAT": "HEX_FLOAT",
    "DECFCON": "DEC_FLOAT", "DEC_FLOAT": "DEC_FLOAT",
    "STRCON": "STRING_CONST", "STRING_CONST": "STRING_CONST",

    # 关键字
    "CONSTTK": "CONST", "CONST": "CONST",
    "INTTK": "INT", "INT": "INT",
    "FLOATTK": "FLOAT", "FLOAT": "FLOAT",
    "VOIDTK": "VOID", "VOID": "VOID",
    "IFTK": "IF", "IF": "IF",
    "ELSETK": "ELSE", "ELSE": "ELSE",
    "WHILETK": "WHILE", "WHILE": "WHILE",
    "BREAKTK": "BREAK", "BREAK": "BREAK",
    "CONTINUETK": "CONTINUE", "CONTINUE": "CONTINUE",
    "RETURNTK": "RETURN", "RETURN": "RETURN",

    # 运算符
    "PLUS": "PLUS",
    "MINU": "MINUS", "MINUS": "MINUS",
    "MULT": "MULTIPLY", "MULTIPLY": "MULTIPLY",
    "DIV": "DIVIDE", "DIVIDE": "DIVIDE",
    "MOD": "MODULO", "MODULO": "MODULO",

    # 关系运算符
    "LSS": "LESS", "LESS": "LESS",
    "LEQ": "LESS_EQUAL", "LESS_EQUAL": "LESS_EQUAL",
    "GRE": "GREATER", "GREATER": "GREATER",
    "GEQ": "GREATER_EQUAL", "GREATER_EQUAL": "GREATER_EQUAL",
    "EQL": "EQUAL", "EQUAL": "EQUAL",
    "NEQ": "NOT_EQUAL", "NOT_EQUAL": "NOT_EQUAL",

    # 逻辑运算符
    "NOT": "LOGICAL_NOT", "LOGICAL_NOT": "LOGICAL_NOT",
    "AND": "LOGICAL_AND", "LOGICAL_AND": "LOGICAL_AND",
    "OR": "LOGICAL_OR", "LOGICAL_OR": "LOGICAL_OR",

    # 赋值
    "ASSIGN": "ASSIGN",

    # 分隔符
    "SEMICN": "SEMICOLON", "SEMICOLON": "SEMICOLON",
    "COMMA": "COMMA",
    "LPARENT": "LEFT_PAREN", "LEFT_PAREN": "LEFT_PAREN",
    "RPARENT": "RIGHT_PAREN", "RIGHT_PAREN": "RIGHT_PAREN",
    "LBRACK": "LEFT_BRACKET", "LEFT_BRACKET": "LEFT_BRACKET",
    "RBRACK": "RIGHT_BRACKET", "RIGHT_BRACKET": "RIGHT_BRACKET",
    "LBRACE": "LEFT_BRACE", "LEFT_BRACE": "LEFT_BRACE",
    "RBRACE": "RIGHT_BRACE", "RIGHT_BRACE": "RIGHT_BRACE",

    # 其他
    "EOF": "EOF"
}

# 设置颜色输出
class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    ENDC = '\033[0m'

def print_color(text, color):
    """打印彩色文本"""
    print(f"{color}{text}{Colors.ENDC}")

def print_debug(text):
    """打印调试信息"""
    if DEBUG:
        print_color(f"[DEBUG] {text}", Colors.YELLOW)

def ensure_dir_exists(dir_path):
    """确保目录存在"""
    if not os.path.exists(dir_path):
        os.makedirs(dir_path)

def compile_project():
    """编译当前项目"""
    try:
        print_color("编译项目...", Colors.BLUE)
        
        # 确保输出目录存在
        ensure_dir_exists(f"{PROJECT_OUT}/production")
        
        # 查找所有Java文件
        java_files = []
        for root, dirs, files in os.walk(PROJECT_SRC):
            for file in files:
                if file.endswith(".java"):
                    java_files.append(os.path.join(root, file))
        
        # 编译所有Java文件
        compile_cmd = [
            "javac", 
            "-d", f"{PROJECT_OUT}/production",
            "-encoding", "UTF-8"
        ] + java_files
        
        result = subprocess.run(
            compile_cmd,
            stderr=subprocess.PIPE,
            check=True
        )
        
        print_color("编译成功", Colors.GREEN)
        return True
    except subprocess.CalledProcessError as e:
        print_color(f"编译失败: {e.stderr.decode('utf-8')}", Colors.RED)
        return False

def run_reference_jar(input_file, output_file, mode=None):
    """运行参考jar包
    mode参数只是为了兼容接口，实际上不会使用
    """
    try:
        # 根据帮助信息，TestLexer.jar的用法是: java TestLexer <输入文件> <输出文件>
        cmd = ["java", "-jar", REF_JAR_PATH, input_file, output_file]
        print_debug(f"运行参考jar包: {' '.join(cmd)}")
        
        result = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30
        )
        
        if result.returncode != 0:
            print_debug(f"参考jar包运行失败: {result.stderr}")
            return None
            
        # 检查输出文件
        if os.path.exists(output_file) and os.path.getsize(output_file) > 0:
            with open(output_file, 'r', encoding='utf-8') as f:
                content = f.read()
            print_debug("从输出文件获取结果成功")
            return content
        
        print_debug("输出文件为空或不存在")
        return None
    except subprocess.TimeoutExpired:
        print_debug("命令执行超时")
        return None
    except Exception as e:
        print_debug(f"命令执行异常: {str(e)}")
        return None

def extract_tokenlist_from_output(output):
    """从输出中提取TokenList部分"""
    if output is None:
        return None
        
    # 查找TokenList相关输出
    token_pattern = r"=================== Token List ===================(.*?)==================="
    match = re.search(token_pattern, output, re.DOTALL)
    if match:
        return match.group(1).strip()
    
    return output  # 如果找不到特定格式，返回原始输出

def extract_ast_from_output(output):
    """从输出中提取AST部分"""
    if output is None:
        return None
        
    # 查找AST相关输出
    ast_pattern = r"====================== AST ======================(.*?)==================="
    match = re.search(ast_pattern, output, re.DOTALL)
    if match:
        return match.group(1).strip()
    
    return output  # 如果找不到特定格式，返回原始输出

def run_reference_lexer(input_file):
    """运行参考jar包的词法分析器"""
    output_file = tempfile.NamedTemporaryFile(delete=False, suffix=".txt").name
    try:
        result = run_reference_jar(input_file, output_file)
        if os.path.exists(output_file):
            os.unlink(output_file)
        return extract_tokenlist_from_output(result)
    except Exception as e:
        print_debug(f"运行参考词法分析器异常: {str(e)}")
        if os.path.exists(output_file):
            os.unlink(output_file)
        return None

def run_reference_parser(input_file):
    """运行参考jar包的语法分析器"""
    output_file = tempfile.NamedTemporaryFile(delete=False, suffix=".txt").name
    try:
        result = run_reference_jar(input_file, output_file)
        if os.path.exists(output_file):
            os.unlink(output_file)
        return extract_ast_from_output(result)
    except Exception as e:
        print_debug(f"运行参考语法分析器异常: {str(e)}")
        if os.path.exists(output_file):
            os.unlink(output_file)
        return None

def run_current_lexer(input_file):
    """运行当前项目的词法分析器"""
    output_file = tempfile.NamedTemporaryFile(delete=False, suffix=".txt").name
    try:
        # 运行我们的TestLexer类
        cmd = ["java", "-cp", CLASS_PATH, "TestLexer", input_file, output_file]
        print_debug(f"运行当前词法分析器: {' '.join(cmd)}")
        
        result = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        
        if result.returncode != 0:
            print_debug(f"当前词法分析器运行失败: {result.stderr}")
            if os.path.exists(output_file):
                os.unlink(output_file)
            return None
            
        with open(output_file, 'r', encoding='utf-8') as f:
            content = f.read()
        os.unlink(output_file)
        return extract_tokenlist_from_output(content)
    except Exception as e:
        print_debug(f"运行当前词法分析器异常: {str(e)}")
        if os.path.exists(output_file):
            os.unlink(output_file)
        return None

def run_current_parser(input_file):
    """运行当前项目的语法分析器"""
    output_file = tempfile.NamedTemporaryFile(delete=False, suffix=".txt").name
    try:
        # 运行我们的TestParser类
        cmd = ["java", "-cp", CLASS_PATH, "TestParser", input_file, output_file]
        print_debug(f"运行当前语法分析器: {' '.join(cmd)}")
        
        result = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        
        if result.returncode != 0:
            print_debug(f"当前语法分析器运行失败: {result.stderr}")
            if os.path.exists(output_file):
                os.unlink(output_file)
            return None
            
        with open(output_file, 'r', encoding='utf-8') as f:
            content = f.read()
        os.unlink(output_file)
        return extract_ast_from_output(content)
    except Exception as e:
        print_debug(f"运行当前语法分析器异常: {str(e)}")
        if os.path.exists(output_file):
            os.unlink(output_file)
        return None

def normalize_output(output):
    """规范化输出（去除不重要的差异）"""
    if output is None:
        return []
    
    # 去除空行和前后空白
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    
    # 去除标题行和分隔线
    filtered_lines = []
    for line in lines:
        if "=======" in line:
            continue  # 跳过分隔线/标题

        # 统一Token类型名称，便于比对
        m = re.match(r"(\d+\s*:\s*)(\w+)(.*)", line)
        if m:
            prefix, token, suffix = m.groups()
            unified_token = TOKEN_MAP.get(token, token)
            line = f"{prefix}{unified_token}{suffix}"
        filtered_lines.append(line)
    
    return filtered_lines

def compare_outputs(ref_output, current_output, test_name, test_type, input_content=None):
    """比较参考输出和当前输出"""
    if ref_output is None or current_output is None:
        # 保存输出(空字符串表示无法获取)
        ensure_dir_exists(FAILED_DIR)
        safe_test_name = test_name.replace(os.sep, '_')
        ref_path = os.path.join(FAILED_DIR, f"{safe_test_name}_target.txt")
        cur_path = os.path.join(FAILED_DIR, f"{safe_test_name}_ans.txt")
        inp_path = os.path.join(FAILED_DIR, f"{safe_test_name}_input.txt")
        try:
            mapped_ref = '\n'.join(normalize_output(ref_output)) if ref_output is not None else ''
            mapped_cur = '\n'.join(normalize_output(current_output)) if current_output is not None else ''
            with open(ref_path, 'w', encoding='utf-8') as f_ref:
                f_ref.write(mapped_ref)
            with open(cur_path, 'w', encoding='utf-8') as f_cur:
                f_cur.write(mapped_cur)
            if input_content is not None:
                with open(inp_path, 'w', encoding='utf-8') as f_inp:
                    f_inp.write(input_content)
            print_color(f"已保存失败输出到 {FAILED_DIR}", Colors.YELLOW)
        except Exception as e:
            print_color(f"保存失败输出时出错: {str(e)}", Colors.RED)

    if ref_output is None:
        print_color(f"{test_type} 测试 {test_name} 失败: 无法获取参考输出", Colors.RED)
        return False
    
    if current_output is None:
        print_color(f"{test_type} 测试 {test_name} 失败: 无法获取当前输出", Colors.RED)
        return False
    
    # 规范化输出
    ref_lines = normalize_output(ref_output)
    current_lines = normalize_output(current_output)
    
    if ref_lines == current_lines:
        print_color(f"{test_type} 测试 {test_name} 通过", Colors.GREEN)
        return True
    else:
        print_color(f"{test_type} 测试 {test_name} 失败: 输出不匹配", Colors.RED)
        
        # 显示详细差异（仅显示前5个差异）
        diff = list(difflib.unified_diff(
            ref_lines, current_lines, 
            fromfile=f"参考输出", tofile=f"当前输出", 
            lineterm=''
        ))
        
        # 限制差异显示的数量
        max_diff_lines = 10
        if len(diff) > max_diff_lines:
            for line in diff[:max_diff_lines]:
                if line.startswith('+'):
                    print_color(line, Colors.GREEN)
                elif line.startswith('-'):
                    print_color(line, Colors.RED)
                else:
                    print(line)
            print_color(f"...（差异太多，仅显示前{max_diff_lines}行）", Colors.YELLOW)
        else:
            for line in diff:
                if line.startswith('+'):
                    print_color(line, Colors.GREEN)
                elif line.startswith('-'):
                    print_color(line, Colors.RED)
                else:
                    print(line)
        
        # 将失败的参考输出和当前输出保存到文件中
        ensure_dir_exists(FAILED_DIR)
        safe_test_name = test_name.replace(os.sep, '_')
        ref_path = os.path.join(FAILED_DIR, f"{safe_test_name}_target.txt")
        cur_path = os.path.join(FAILED_DIR, f"{safe_test_name}_ans.txt")
        inp_path = os.path.join(FAILED_DIR, f"{safe_test_name}_input.txt")
        try:
            mapped_ref = '\n'.join(ref_lines)
            mapped_cur = '\n'.join(current_lines)
            with open(ref_path, 'w', encoding='utf-8') as f_ref:
                f_ref.write(mapped_ref)
            with open(cur_path, 'w', encoding='utf-8') as f_cur:
                f_cur.write(mapped_cur)
            if input_content is not None:
                with open(inp_path, 'w', encoding='utf-8') as f_inp:
                    f_inp.write(input_content)
            print_color(f"已保存失败输出到 {FAILED_DIR}", Colors.YELLOW)
        except Exception as e:
            print_color(f"保存失败输出时出错: {str(e)}", Colors.RED)
        
        return False

def test_jar_functionality():
    """测试jar包功能，确认它的用法"""
    print_color("测试参考jar包功能...", Colors.BLUE)
    print_color(f"使用jar包: {REF_JAR_PATH}", Colors.BLUE)
    
    if not os.path.exists(REF_JAR_PATH):
        print_color(f"错误: 找不到jar包 {REF_JAR_PATH}", Colors.RED)
        return False
        
    # 找一个简单的测试样例
    test_dirs = [d for d in os.listdir(TESTS_DIR) if os.path.isdir(os.path.join(TESTS_DIR, d))]
    if not test_dirs:
        print_color("找不到测试样例", Colors.RED)
        return False
        
    test_dir = test_dirs[0]  # 使用第一个测试样例
    input_file = os.path.join(TESTS_DIR, test_dir, "input.txt")
    output_file = tempfile.NamedTemporaryFile(delete=False, suffix=".txt").name
    
    if not os.path.exists(input_file):
        print_color(f"找不到测试样例文件: {input_file}", Colors.RED)
        return False
    
    print_color(f"使用测试样例: {test_dir}", Colors.BLUE)
    
    # 尝试直接运行jar包，查看它的输出或错误信息
    try:
        result = subprocess.run(
            ["java", "-jar", REF_JAR_PATH, "--help"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        
        print_color("参考jar包帮助信息:", Colors.BLUE)
        if result.stdout:
            print(result.stdout)
        if result.stderr and "Unable to access jarfile" not in result.stderr:
            print(result.stderr)
            
        # 尝试按正确用法运行jar包
        print_color("\n尝试按正确用法运行jar包:", Colors.BLUE)
        cmd = ["java", "-jar", REF_JAR_PATH, input_file, output_file]
        print_color(f"运行命令: {' '.join(cmd)}", Colors.BLUE)
        
        result = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        
        if result.returncode == 0:
            if os.path.exists(output_file) and os.path.getsize(output_file) > 0:
                with open(output_file, 'r', encoding='utf-8') as f:
                    content = f.read()
                print_color("成功获取输出:", Colors.GREEN)
                print(content[:500] + "..." if len(content) > 500 else content)
                
                # 检查输出中是否包含TokenList和AST
                if "Token List" in content:
                    print_color("\n输出中包含TokenList信息", Colors.GREEN)
                    token_list = extract_tokenlist_from_output(content)
                    print(token_list[:200] + "..." if len(token_list) > 200 else token_list)
                else:
                    print_color("输出中不包含TokenList信息", Colors.RED)
                    
                if "AST" in content:
                    print_color("\n输出中包含AST信息", Colors.GREEN)
                    ast = extract_ast_from_output(content)
                    print(ast[:200] + "..." if len(ast) > 200 else ast)
                else:
                    print_color("输出中不包含AST信息", Colors.RED)
            else:
                print_color("输出文件为空或不存在", Colors.RED)
        else:
            print_color(f"命令执行失败，返回码: {result.returncode}", Colors.RED)
            if result.stderr:
                print(result.stderr)
            
        # 清理
        if os.path.exists(output_file):
            os.unlink(output_file)
            
        return True
    except Exception as e:
        print_color(f"测试jar包功能时出错: {str(e)}", Colors.RED)
        if os.path.exists(output_file):
            os.unlink(output_file)
        return False

def test_all_cases():
    """测试所有测试用例"""
    if not os.path.exists(REF_JAR_PATH):
        print_color(f"错误: 找不到参考jar包 {REF_JAR_PATH}", Colors.RED)
        return

    # 测试jar包功能
    if DEBUG:
        test_jar_functionality()
        
    if not compile_project():
        print_color("项目编译失败，无法进行测试", Colors.RED)
        return
    
    test_dirs = sorted([f for f in os.listdir(TESTS_DIR) if os.path.isdir(os.path.join(TESTS_DIR, f))])
    
    lexer_success = 0
    lexer_total = 0
    parser_success = 0
    parser_total = 0
    
    print_color("\n开始测试...", Colors.BLUE)
    print_color(f"使用参考jar包: {REF_JAR_PATH}", Colors.BLUE)
    print_color(f"测试模式: {TEST_MODE}", Colors.BLUE)
    print(f"共找到 {len(test_dirs)} 个测试样例")
    
    # 先测试一个样例，确认可以正常获取结果
    if test_dirs and DEBUG:
        test_dir = test_dirs[0]
        test_path = os.path.join(TESTS_DIR, test_dir)
        input_file = os.path.join(test_path, "input.txt")
        
        print_color(f"\n测试第一个样例的参考输出: {test_dir}", Colors.BLUE)
        if TEST_MODE in ['both', 'lexer']:
            ref_lexer_output = run_reference_lexer(input_file)
            if ref_lexer_output:
                print_color("成功获取参考词法分析输出:", Colors.GREEN)
                print(ref_lexer_output[:200] + "..." if len(ref_lexer_output) > 200 else ref_lexer_output)
            else:
                print_color("无法获取参考词法分析输出", Colors.RED)
            
        if TEST_MODE in ['both', 'parser']:
            ref_parser_output = run_reference_parser(input_file)
            if ref_parser_output:
                print_color("成功获取参考语法分析输出:", Colors.GREEN)
                print(ref_parser_output[:200] + "..." if len(ref_parser_output) > 200 else ref_parser_output)
            else:
                print_color("无法获取参考语法分析输出", Colors.RED)
    
    start_time = time.time()
    
    for test_dir in test_dirs:
        test_path = os.path.join(TESTS_DIR, test_dir)
        input_file = os.path.join(test_path, "input.txt")
        
        if not os.path.exists(input_file):
            print_color(f"跳过 {test_dir}: 未找到 input.txt", Colors.YELLOW)
            continue
        
        print(f"\n测试样例: {test_dir}")
        
        # 读取输入内容，用于失败日志
        with open(input_file, 'r', encoding='utf-8') as f_in:
            input_content = f_in.read()
        
        # 测试词法分析
        if TEST_MODE in ['both', 'lexer']:
            ref_lexer_output = run_reference_lexer(input_file)
            current_lexer_output = run_current_lexer(input_file)
            
            if ref_lexer_output is not None:
                lexer_total += 1
                if compare_outputs(ref_lexer_output, current_lexer_output, test_dir, "词法分析", input_content):
                    lexer_success += 1
        
        # 测试语法分析
        if TEST_MODE in ['both', 'parser']:
            ref_parser_output = run_reference_parser(input_file)
            current_parser_output = run_current_parser(input_file)
            
            if ref_parser_output is not None:
                parser_total += 1
                if compare_outputs(ref_parser_output, current_parser_output, test_dir, "语法分析", input_content):
                    parser_success += 1
    
    end_time = time.time()
    elapsed_time = end_time - start_time
    
    print_color("\n测试结果汇总:", Colors.BLUE)
    if TEST_MODE in ['both', 'lexer']:
        print_color(f"词法分析: {lexer_success}/{lexer_total} 通过 ({lexer_success/lexer_total*100:.1f}%)" if lexer_total > 0 else "词法分析: 无结果", 
            Colors.GREEN if lexer_success == lexer_total and lexer_total > 0 else Colors.RED)
    if TEST_MODE in ['both', 'parser']:
        print_color(f"语法分析: {parser_success}/{parser_total} 通过 ({parser_success/parser_total*100:.1f}%)" if parser_total > 0 else "语法分析: 无结果", 
            Colors.GREEN if parser_success == parser_total and parser_total > 0 else Colors.RED)
    print_color(f"总耗时: {elapsed_time:.2f} 秒", Colors.BLUE)

def show_help():
    """显示帮助信息"""
    print_color("SysY Lexer & Parser 评测脚本", Colors.BLUE)
    print("用法: python evaluator.py [选项]")
    print("\n选项:")
    print("  -h, --help      显示此帮助信息")
    print("  --ref=JAR_PATH  设置参考实现的jar包路径")
    print("  --debug         启用调试模式，显示更多信息")
    print("  --test-jar      测试参考jar包功能")
    print("  --lexer         仅测试词法分析")
    print("  --parser        仅测试语法分析")
    print("\n示例:")
    print("  python evaluator.py --ref=./TestLexer.jar --debug")
    print("\n说明:")
    print("  该脚本将编译当前项目，并与参考jar包进行对比测试")
    print("  测试样例位于tests目录下，每个子目录为一个测试样例")

def parse_args():
    """解析命令行参数"""
    global REF_JAR_PATH, DEBUG, TEST_MODE
    
    i = 1
    while i < len(sys.argv):
        arg = sys.argv[i]
        if arg in ('-h', '--help'):
            show_help()
            sys.exit(0)
        elif arg.startswith('--ref='):
            REF_JAR_PATH = arg[6:]
            print_color(f"使用参考jar包: {REF_JAR_PATH}", Colors.BLUE)
        elif arg == '--ref' and i + 1 < len(sys.argv):
            REF_JAR_PATH = sys.argv[i + 1]
            print_color(f"使用参考jar包: {REF_JAR_PATH}", Colors.BLUE)
            i += 1
        elif arg == '--debug':
            DEBUG = True
        elif arg == '--test-jar':
            test_jar_functionality()
            sys.exit(0)
        elif arg == '--lexer':
            TEST_MODE = 'lexer'
            print_color("仅测试词法分析", Colors.BLUE)
        elif arg == '--parser':
            TEST_MODE = 'parser'
            print_color("仅测试语法分析", Colors.BLUE)
        i += 1

if __name__ == "__main__":
    parse_args()
    test_all_cases() 