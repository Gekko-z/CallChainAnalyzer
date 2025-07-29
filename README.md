# 调用链分析器

一个使用JavaParser分析Spring项目调用链的Java工具。它会追踪从使用该追踪对象到最外层REST控制器方法的调用链。

## 特性

- 查找Spring项目中追踪对象的所有使用位置
- 追踪从常量使用到REST控制器端点的调用链
- 支持调试模式以了解分析过程
- 使用JavaParser进行准确的Java代码解析
- 兼容Java 1.8环境

## 先决条件

- Java 1.8或更高版本

## 构建

1. 构建项目：
   ```bash
   mvn clean install
   ```

2. 构建完成后：
   - 核心模块jar包位于 `call-chain-analyzer-core/target/call-chain-analyzer-core-1.0-SNAPSHOT.jar`
   - CLI模块可执行jar包位于 `call-chain-analyzer-cli/target/call-chain-analyzer-cli-1.0-SNAPSHOT.jar`

## 使用方法

运行工具：
```
java -jar call-chain-analyzer-cli/target/call-chain-analyzer-cli-1.0-SNAPSHOT.jar <项目路径> <查询类型> <查询关键字>
```

使用调试输出：
```
java -jar call-chain-analyzer-cli/target/call-chain-analyzer-cli-1.0-SNAPSHOT.jar <项目路径> <查询类型> <查询关键字> --debug
```

### 查询类型说明

| 类型值 | 查询类型     | 查询关键字示例           | 说明 |
|--------|--------------|--------------------------|------|
| 0      | Mapper类     | `AppInfoMapper`          | 追踪指定Mapper类的所有方法调用链 |
| 1      | 方法调用     | `AppInfoMapper#getDeviceList` | 追踪指定方法的调用链 |
| 2      | 常量         | `DEFAULT_MESSAGE`        | 追踪指定常量的使用调用链 |

## 示例

追踪项目`/path/to/project`中名为`DEFAULT_MESSAGE`的常量的调用链：

```
java -jar call-chain-analyzer-cli-1.0-SNAPSHOT.jar /path/to/project 2 DEFAULT_MESSAGE
```

追踪项目中`UserMapper`类的调用链：

```
java -jar call-chain-analyzer-cli-1.0-SNAPSHOT.jar /path/to/project 0 UserMapper
```

追踪项目中`UserService#getUserById`方法的调用链：

```
java -jar call-chain-analyzer-cli-1.0-SNAPSHOT.jar /path/to/project 1 UserService#getUserById
```


使用调试模式：
```
java -jar call-chain-analyzer-cli-1.0-SNAPSHOT.jar /path/to/project 2 DEFAULT_MESSAGE --debug
```

## 模块说明

### call-chain-analyzer-core
包含核心分析逻辑：
- `CallChainAnalyzer`：核心分析器类
- `descriptor`包：包含方法描述符类
- 依赖JavaParser进行代码解析

### call-chain-analyzer-cli
提供命令行接口：
- `Main.java`：程序入口点
- 处理命令行参数
- 调用核心模块进行分析
- 格式化输出结果

## 适用场景

- **影响分析**：分析代码变更可能影响的接口范围
- **代码审查**：快速了解特定功能点的调用范围
- **故障排查**：定位特定接口涉及的业务逻辑链路
- **重构支持**：评估重构对REST接口的影响
- **文档生成**：自动生成接口调用关系文档
