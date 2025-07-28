# 调用链分析器

一个使用JavaParser分析Spring项目调用链的Java工具。给定一个常量名称，它会追踪从使用该常量到最外层REST控制器方法的调用链。

## 特性

- 查找Spring项目中常量的所有使用位置
- 追踪从常量使用到REST控制器端点的调用链
- 准确处理重载方法（使用方法签名区分）
- 防止追踪过程中的无限递归
- 支持调试模式以了解分析过程
- 使用JavaParser进行准确的Java代码解析
- 兼容Java 1.8环境
- 使用空间换时间策略优化性能

## 先决条件

- Java 1.8或更高版本

## 构建

1. 编译项目：
   ```
   javac -cp "lib/javaparser-core-3.25.5.jar" -d target/classes src/main/java/com/example/callchain/*.java
   ```

2. 创建包含所有依赖的JAR文件：
   ```
   mkdir -p temp && cd temp
   jar -xf ../lib/javaparser-core-3.25.5.jar
   cp -r ../target/classes/* .
   jar cfe ../call-chain-analyzer.jar com.github.callchain.Main .
   cd ..
   ```

## 使用方法

运行工具：
```
java -jar call-chain-analyzer.jar <项目路径> <查询类型> <查询关键字>
```

使用调试输出：
```
java -jar call-chain-analyzer.jar <项目路径> <查询类型> <查询关键字> --debug
```

使用提供的脚本：
```
./run.sh <项目路径> <查询类型> <查询关键字> [--debug]
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
java -jar call-chain-analyzer.jar /path/to/project 2 DEFAULT_MESSAGE
```

追踪项目中`UserMapper`类的调用链：

```
java -jar call-chain-analyzer.jar /path/to/project 0 UserMapper
```

追踪项目中`UserService#getUserById`方法的调用链：

```
java -jar call-chain-analyzer.jar /path/to/project 1 UserService#getUserById
```


使用调试模式：
```
java -jar call-chain-analyzer.jar /path/to/project 2 DEFAULT_MESSAGE --debug
```

## 实现细节

分析器按以下步骤工作：

1. **解析项目**：使用JavaParser解析项目中的所有Java文件，构建AST（抽象语法树）表示。

2. **构建缓存**：预先构建以下缓存以提高查询性能：
   - 所有方法定义
   - 所有方法调用关系
   - REST控制器方法
   - 常量使用位置
   - 字段声明（用于依赖注入）
   - 接口中的REST映射信息
   - Controller方法的URL映射信息

3. **查找入口点**：根据查询类型扫描缓存以定位入口点方法。

4. **追踪调用链**：对于每个入口点方法，向上追踪调用层次结构，直到找到REST控制器方法。

5. **识别REST控制器**：查找使用`@RestController`或`@Controller`注解的类，以及使用REST映射注解（如`@GetMapping`、`@PostMapping`等）的方法。

6. **处理方法调用**：
   - 检测完全限定方法调用（`ClassName.methodName()`）
   - 检测同类中的方法调用（包括this调用和隐式调用）
   - 处理注入依赖上的方法调用
   - 使用方法签名正确区分重载方法
   - 正确处理父类方法调用（super调用）

7. **URL映射提取**：
   - 提取类级别的@RequestMapping映射路径
   - 提取方法级别的REST注解映射路径
   - 处理接口中定义的映射信息
   - 合并类路径和方法路径生成完整URL

8. **防止无限循环**：使用访问集合防止追踪过程中的无限递归和循环依赖。

## 使用JavaParser的好处

- **准确性**：正确解析Java代码而不是使用字符串匹配
- **方法重载**：通过使用方法签名正确处理重载方法
- **类型解析**：可以更准确地解析类型和方法调用
- **可维护性**：比基于字符串的解析更容易扩展和维护

## 性能优化

- **预缓存机制**：一次性解析项目所有文件，避免重复解析
- **空间换时间**：预先构建完整调用关系缓存，提高查询速度
- **递归优化**：使用visited集合避免重复计算和无限递归
- **精确匹配**：通过类名、方法名和签名精确匹配方法，避免误判

## 适用场景

- **影响分析**：分析代码变更可能影响的接口范围
- **代码审查**：快速了解特定功能点的调用范围
- **故障排查**：定位特定接口涉及的业务逻辑链路
- **重构支持**：评估重构对REST接口的影响
- **文档生成**：自动生成接口调用关系文档
