#!/bin/bash

# 调用链分析器运行脚本

if [ $# -lt 2 ]; then
    echo "用法: $0 <项目路径> <常量名称> [--debug]"
    exit 1
fi

PROJECT_PATH=$1
CONSTANT_NAME=$2
DEBUG_FLAG=$3

# 检查项目路径是否存在
if [ ! -d "$PROJECT_PATH" ]; then
    echo "错误: 项目路径 '$PROJECT_PATH' 不存在"
    exit 1
fi

# 编译和运行分析器
cd "$(dirname "$0")"

# 编译Java文件
echo "正在编译调用链分析器..."
javac -cp "lib/javaparser-core-3.25.5.jar" -d target/classes src/main/java/com/example/callchain/*.java

if [ $? -ne 0 ]; then
    echo "错误: 编译失败"
    exit 1
fi

# 创建包含所有依赖的JAR文件
echo "正在创建JAR文件..."
mkdir -p temp && cd temp
jar -xf ../lib/javaparser-core-3.25.5.jar
cp -r ../target/classes/* .
jar cfe ../call-chain-analyzer.jar com.example.callchain.Main .
cd ..

# 运行分析器
echo "正在运行调用链分析器..."
if [ "$DEBUG_FLAG" = "--debug" ]; then
    java -jar call-chain-analyzer.jar "$PROJECT_PATH" "$CONSTANT_NAME" --debug
else
    java -jar call-chain-analyzer.jar "$PROJECT_PATH" "$CONSTANT_NAME"
fi