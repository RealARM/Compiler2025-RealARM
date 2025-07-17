# LLVM 评测机

这是一个用于评测LLVM IR文件的Python工具，它可以验证LLVM文件的输出和返回值是否符合预期。

## 功能

- 执行LLVM IR文件（.ll）并验证其输出和返回值
- 支持带有输入的LLVM程序
- 对比实际输出与预期输出
- 详细的测试报告

## 目录结构

```
├── llvm_evaluator.py     # 主评测工具
├── testcases/            # 测试用例目录
│   ├── *.ll              # LLVM IR文件
│   ├── input/            # 输入文件目录
│   │   └── *_input.txt   # 输入文件
│   ├── expected/         # 期望输出目录
│   │   └── *_output.txt  # 期望输出文件
│   └── output/           # 实际输出目录（自动生成）
└── README.md             # 说明文档
```

## 使用方法

### 基本使用

```bash
python llvm_evaluator.py
```

这将测试`testcases/`目录下的所有LLVM IR文件。

### 高级使用

```bash
python llvm_evaluator.py [options] [files...]
```

选项:
- `--llvm-path PATH` - LLVM解释器路径，默认为"lli"
- `--testcase-dir DIR` - 测试用例目录，默认为"testcases"
- `--input-dir DIR` - 输入文件目录（相对于testcase-dir），默认为"input"
- `--output-dir DIR` - 输出文件目录（相对于testcase-dir），默认为"output"
- `--expected-dir DIR` - 期望输出目录（相对于testcase-dir），默认为"expected"
- `--expected-return N` - 期望的返回值，默认为0

### 示例

1. 测试指定的LLVM文件:
   ```bash
   python llvm_evaluator.py testcases/hello_world.ll testcases/sum.ll
   ```

2. 使用自定义的LLVM解释器路径:
   ```bash
   python llvm_evaluator.py --llvm-path /path/to/lli
   ```

3. 指定非零返回值:
   ```bash
   python llvm_evaluator.py --expected-return 42 testcases/return_value.ll
   ```

## 命名约定

评测机会根据以下命名约定自动查找输入和期望输出文件:

- 输入文件: `{test_name}_input.txt`, `{test_name}.in`, 或 `input_{test_name}.txt`
- 期望输出文件: `{test_name}_output.txt`, `{test_name}.out`, `{test_name}.txt`, 或 `expected_{test_name}.txt`

其中 `{test_name}` 是LLVM文件的名称（不含扩展名）。

## 要求

- Python 3.6+
- LLVM工具链（需要安装'lli'命令行工具）

## 扩展

如果需要自定义评测逻辑，可以通过继承`LLVMEvaluator`类并重写相关方法来实现。 