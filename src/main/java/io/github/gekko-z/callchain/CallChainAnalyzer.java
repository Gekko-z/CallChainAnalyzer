package com.github.callchain;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.ParserConfiguration;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主类，用于分析Spring项目中的调用链。
 * 给定一个常量名称，它会追踪从使用该常量到最外层REST控制器方法的调用链。
 * 使用空间换时间的策略，预先构建完整的缓存以提高查询性能。
 */
public class CallChainAnalyzer {

    private String projectPath;
    private String searchType;
    private String searchKey;
    private boolean debug;

    // 缓存所有解析的文件
    private final Map<String, CompilationUnit> parsedFiles = new HashMap<>();

    // 缓存类名到文件路径的映射
    private final Map<String, String> classNameToFileMap = new HashMap<>();

    // 缓存所有方法定义
    private final Map<String, List<MethodDefinition>> methodDefinitions = new HashMap<>();

    // 缓存所有方法调用关系 (被调用方法 -> 调用者方法)
    private final Map<String, Set<String>> methodCallers = new HashMap<>();

    // 缓存REST控制器方法
    private final Set<String> restControllerMethods = new HashSet<>();

    // 缓存常量使用位置
    private final Map<String, List<MethodIdentifier>> constantUsages = new HashMap<>();

    // 缓存字段声明（用于依赖注入）
    private final Map<String, Map<String, String>> fieldDeclarations = new HashMap<>();

    // 预定义的常量名称列表
    private final Set<String> constantNames = new HashSet<>();

    // 缓存接口中的REST映射信息
    private final Map<String, Map<String, String>> interfaceMethodMappings = new HashMap<>();

    // 缓存Controller方法的URL映射信息
    private final Map<String, ControllerMethodInfo> controllerMethodUrls = new HashMap<>();

    public CallChainAnalyzer(String projectPath, String searchType, String searchKey, boolean debug) {
        this.projectPath = projectPath;
        this.searchType = searchType;
        this.searchKey = searchKey;
        this.debug = debug;

        // 配置JavaParser以兼容Java 8
        StaticJavaParser.setConfiguration(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8));
        parseProject();
        buildCaches();
    }

    /**
     * 收集接口中的方法映射信息
     */
    private void collectInterfaceMappings(ClassOrInterfaceDeclaration interfaceDecl, String interfaceName) {
        Map<String, String> methodMappings = new HashMap<>();

        for (MethodDeclaration md : interfaceDecl.getMethods()) {
            String methodSignature = getMethodSignature(md);
            String mapping = extractMethodLevelMapping(md);
            if (mapping != null) {
                methodMappings.put(md.getNameAsString() + "#" + methodSignature, mapping);
                if (debug)
                    System.out.println("接口方法映射: " + interfaceName + "#" + md.getNameAsString() + " -> " + mapping);
            }
        }

        if (!methodMappings.isEmpty()) {
            interfaceMethodMappings.put(interfaceName, methodMappings);
        }
    }

    /**
     * 提取方法级别的映射路径
     */
    private String extractMethodLevelMapping(MethodDeclaration md) {
        // 检查各种REST注解
        String[] restAnnotations = {"GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping"};

        for (String annotation : restAnnotations) {
            String mapping = getMappingValue(md, annotation);
            if (mapping != null) {
                return mapping;
            }
        }

        return null;
    }

    /**
     * 从注解中提取映射路径值
     */
    private String getMappingValue(NodeWithAnnotations<?> node, String annotationName) {
        if (node.isAnnotationPresent(annotationName)) {
            Optional<AnnotationExpr> annotationOpt = node.getAnnotationByName(annotationName);
            if (annotationOpt.isPresent()) {
                AnnotationExpr annotation = annotationOpt.get();
                if (annotation instanceof SingleMemberAnnotationExpr) {
                    // @RequestMapping("/path") 格式
                    Expression valueExpr = ((SingleMemberAnnotationExpr) annotation).getMemberValue();
                    return extractPathValue(valueExpr);
                } else if (annotation instanceof NormalAnnotationExpr) {
                    // @RequestMapping(value = "/path", method = ...) 格式
                    NormalAnnotationExpr normalAnnotation = (NormalAnnotationExpr) annotation;
                    for (MemberValuePair pair : normalAnnotation.getPairs()) {
                        if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString())) {
                            return extractPathValue(pair.getValue());
                        }
                    }
                } else if (annotation instanceof MarkerAnnotationExpr) {
                    // @RequestMapping() 格式，没有参数
                    return ""; // 默认路径
                }
            }
        }
        return null;
    }

    /**
     * 从表达式中提取路径值
     */
    private String extractPathValue(Expression expr) {
        if (expr instanceof StringLiteralExpr) {
            // "/path" 格式
            return ((StringLiteralExpr) expr).getValue();
        } else if (expr instanceof ArrayInitializerExpr) {
            // {"/path1", "/path2"} 格式，取第一个
            ArrayInitializerExpr arrayExpr = (ArrayInitializerExpr) expr;
            if (!arrayExpr.getValues().isEmpty()) {
                return extractPathValue(arrayExpr.getValues().get(0));
            }
        }
        return null;
    }


    /**
     * 解析项目中的所有Java文件
     */
    private void parseProject() {
        try {
            if (debug) System.out.println("开始解析项目文件...");
            long startTime = System.currentTimeMillis();

            Files.walk(Paths.get(projectPath))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            // 使用StaticJavaParser解析Java文件
                            CompilationUnit cu = StaticJavaParser.parse(new FileInputStream(path.toFile()));
                            parsedFiles.put(path.toString(), cu);

                            // 提取类名
                            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                                classNameToFileMap.put(cls.getNameAsString(), path.toString());
                            });
                        } catch (Exception e) {
                            if (debug) {
                                System.err.println("解析文件失败: " + path + " - " + e.getMessage());
                            }
                        }
                    });

            long endTime = System.currentTimeMillis();
            if (debug) System.out.println("项目解析完成，耗时: " + (endTime - startTime) + "ms");
        } catch (IOException e) {
            throw new RuntimeException("解析项目文件失败", e);
        }
    }

    /**
     * 构建所有必要的缓存
     */
    private void buildCaches() {
        if (debug) System.out.println("开始构建缓存...");
        long startTime = System.currentTimeMillis();

        // 第一遍：收集类和字段信息
        for (Map.Entry<String, CompilationUnit> entry : parsedFiles.entrySet()) {
            CompilationUnit cu = entry.getValue();

            // 提取字段声明
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
                    super.visit(cid, arg);
                    String className = cid.getNameAsString();
                    Map<String, String> fields = new HashMap<>();

                    // 收集字段声明
                    for (FieldDeclaration fd : cid.getFields()) {
                        for (VariableDeclarator vd : fd.getVariables()) {
                            String fieldName = vd.getNameAsString();
                            String fieldType = fd.getElementType().asString();
                            fields.put(fieldName, fieldType);
                            if (debug)
                                System.out.println("字段声明: " + className + "." + fieldName + " : " + fieldType);
                        }
                    }

                    fieldDeclarations.put(className, fields);

                    // 如果是接口，收集接口中的方法映射信息
                    if (cid.isInterface()) {
                        collectInterfaceMappings(cid, className);
                    }
                }
            }, null);
        }

        // 第二遍：构建其他缓存
        for (Map.Entry<String, CompilationUnit> entry : parsedFiles.entrySet()) {
            String filePath = entry.getKey();
            CompilationUnit cu = entry.getValue();

            // 提取方法定义和调用关系
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(MethodDeclaration md, Void arg) {
                    super.visit(md, arg);

                    // 获取包含此方法的类名
                    String className = cu.findAll(ClassOrInterfaceDeclaration.class)
                            .stream()
                            .findFirst()
                            .map(ClassOrInterfaceDeclaration::getNameAsString)
                            .orElse("Unknown");

                    String methodName = md.getNameAsString();
                    String methodSignature = getMethodSignature(md);
                    MethodDefinition methodDef = new MethodDefinition(className, methodName, methodSignature, filePath);

                    // 添加到方法定义缓存
                    methodDefinitions.computeIfAbsent(getMethodKey(className, methodName), k -> new ArrayList<>()).add(methodDef);

                    // 检查是否为REST控制器方法
                    if (isRestControllerMethod(cu, md)) {
                        restControllerMethods.add(methodDef.toString());
                        if (debug) System.out.println("找到REST控制器方法: " + methodDef.toString());

                        // 提取URL映射信息
                        String classLevelMapping = extractClassLevelMapping(cu, className);
                        String methodLevelMapping = extractMethodLevelMappingFromMethodOrInterface(cu, md, className);

                        ControllerMethodInfo controllerInfo = new ControllerMethodInfo(
                                className, methodName, methodSignature,
                                classLevelMapping, methodLevelMapping, filePath);

                        controllerMethodUrls.put(methodDef.toString(), controllerInfo);

                        if (debug)
                            System.out.println("Controller URL映射: " + methodDef.toString() + " -> " + controllerInfo.getFullUrl());
                    }

                    // 循环方法体，根据需要进行处理
                    if (md.getBody().isPresent()) {
                        md.getBody().get().accept(new VoidVisitorAdapter<Void>() {

                            // 在buildCaches方法中增强常量检测逻辑
                            @Override
                            public void visit(FieldAccessExpr fae, Void arg) {
                                super.visit(fae, arg);
                                // 检查是否访问Constants类的字段
                                if (fae.getScope() != null) {
                                    String scopeStr = fae.getScope().toString();

                                    // 处理多种常量引用方式
                                    if ((scopeStr.endsWith("Constants") || scopeStr.equals(searchKey)) && SearchType.CONSTANT.equals(searchType)) {
                                        String constantName = fae.getNameAsString();
                                        MethodIdentifier methodId = new MethodIdentifier(className, methodName, methodSignature, filePath);
                                        constantUsages.computeIfAbsent(constantName, k -> new ArrayList<>()).add(methodId);
                                        if (debug)
                                            System.out.println("在方法 " + className + "." + methodName + " 中找到常量使用: " + constantName);
                                    }
                                }
                            }


                            @Override
                            public void visit(NameExpr ne, Void arg) {
                                super.visit(ne, arg);
                                String name = ne.getNameAsString();
                                // 检查是否为预定义的常量名称
                                if (name.equals(searchKey)) {
                                    MethodIdentifier methodId = new MethodIdentifier(className, methodName, methodSignature, filePath);
                                    constantUsages.computeIfAbsent(name, k -> new ArrayList<>()).add(methodId);
                                    if (debug)
                                        System.out.println("在方法 " + className + "." + methodName + " 中找到常量使用: " + name);
                                }
                            }

                            // 查找方法体中的方法调用
                            @Override
                            public void visit(MethodCallExpr mce, Void arg) {
                                super.visit(mce, arg);

                                if (className == "DataSourceService") {
                                    System.out.println("DataSourceService");
                                    if (methodName == "getPageData") {
                                        System.out.println("getPageData");
                                    }
                                }
                                String callerKey = getMethodKey(className, methodName);
                                String calledMethodName = mce.getNameAsString();

                                // 获取被调用方法的类名
                                String calledClassName = resolveCalledClass(className, mce);

                                // 添加调用关系到缓存 (被调用方法 -> 调用者方法)
                                String calledMethodKey = getMethodKey(calledClassName, calledMethodName);
                                methodCallers.computeIfAbsent(calledMethodKey, k -> new HashSet<>()).add(callerKey);
                                if (debug) System.out.println("方法调用关系: " + callerKey + " -> " + calledMethodKey);

                                // 检查是否是传入的查询关键字，Mapper类型关键字查询，或具体方法类型关键字查询
                                if (("0".equals(searchType) && searchKey.equals(calledClassName)) ||
                                        ("1".equals(searchType) && searchKey.equals(calledMethodKey))) {
                                    // 添加调用关系到缓存 (被调用方法 -> 调用者方法)
                                    MethodIdentifier methodId = new MethodIdentifier(className, methodName, methodSignature, filePath);
                                    constantUsages.computeIfAbsent(calledMethodKey, k -> new ArrayList<>()).add(methodId);
                                    if (debug)
                                        System.out.println("在方法 " + className + "." + methodName + " 中找到常量使用: " + calledMethodName);
                                }
                            }
                        }, null);
                    }
                }
            }, null);
        }

        long endTime = System.currentTimeMillis();
        if (debug) System.out.println("缓存构建完成，耗时: " + (endTime - startTime) + "ms");
        if (debug) System.out.println("缓存统计: " + methodDefinitions.size() + " 个方法定义, " +
                methodCallers.size() + " 个调用关系, " +
                restControllerMethods.size() + " 个REST控制器方法, " +
                constantUsages.size() + " 个常量使用位置");
    }

    /**
     * 解析被调用方法的类名
     */
    private String resolveCalledClass(String callerClassName, MethodCallExpr mce) {
        String calledMethodName = mce.getNameAsString();
        String calledClassName = callerClassName; // 默认为同类调用

        // Java 1.8兼容性处理
        if (mce.getScope().isPresent()) {
            Expression scope = mce.getScope().get();
            String scopeStr = scope.toString();

            // 移除Optional包装（如果存在）
            if (scopeStr.startsWith("Optional[")) {
                scopeStr = scopeStr.substring(9, scopeStr.length() - 1);
            }

            // 处理super调用
            if ("super".equals(scopeStr)) {
                // 查找父类信息
                calledClassName = resolveSuperClass(callerClassName);
                return calledClassName;
            }

            // 处理this调用
            if ("this".equals(scopeStr)) {
                return callerClassName;
            }

            // 处理完全限定名
            if (scopeStr.contains(".")) {
                calledClassName = scopeStr.substring(scopeStr.lastIndexOf('.') + 1);
            }
            // 处理字段访问（依赖注入的情况）
            else if (fieldDeclarations.containsKey(callerClassName) &&
                    fieldDeclarations.get(callerClassName).containsKey(scopeStr)) {
                calledClassName = fieldDeclarations.get(callerClassName).get(scopeStr);
            }
            // 处理其他类的实例调用
            else if (!scopeStr.equals(callerClassName)) {
                calledClassName = scopeStr;
            }
        }
        // 如果没有作用域（即没有前缀），则默认为当前类内部调用
        else {
            calledClassName = callerClassName;
        }

        return calledClassName;
    }

    /**
     * 解析父类名称
     */
    private String resolveSuperClass(String className) {
        String filePath = classNameToFileMap.get(className);
        if (filePath == null) return className;

        CompilationUnit cu = parsedFiles.get(filePath);
        if (cu == null) return className;

        Optional<ClassOrInterfaceDeclaration> classOpt = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (!classOpt.isPresent()) return className;

        ClassOrInterfaceDeclaration classDecl = classOpt.get();
        if (classDecl.getExtendedTypes().isEmpty()) return className;

        // 返回第一个父类
        return classDecl.getExtendedTypes().get(0).getNameAsString();
    }



    /**
     * 查找从常量到REST控制器端点的所有调用链
     *
     * @param constantName 要追踪的常量名称
     * @return 表示调用链的方法签名映射
     */
    public Map<String, List<List<String>>> findAllCallChainsToRestController(String constantName) {
        if (debug) System.out.println("开始查找常量 " + constantName + " 的调用链...");
        long startTime = System.currentTimeMillis();

        Map<String, List<List<String>>> allCallChains = new HashMap<>();

        List<MethodIdentifier> usages = new ArrayList<>();
        // 从缓存中获取常量使用位置
        if ("2".equals(searchType)) {
            usages = constantUsages.getOrDefault(constantName, new ArrayList<>());
        } else {
            for (List<MethodIdentifier> usageList : constantUsages.values()) {
                usages.addAll(usageList);
            }
        }

        if (debug) System.out.println("找到常量 " + constantName + " 的 " + usages.size() + " 个使用位置");

        // 对每个使用位置，追踪调用链到REST控制器
        for (MethodIdentifier usage : usages) {
            if (debug) System.out.println("正在追踪调用链，起始点: " + usage);
            Set<String> visited = new HashSet<>();
            List<List<String>> callChains = traceCallChainFromCache(usage, visited);
            if (!callChains.isEmpty()) {
                allCallChains.put(usage.toString(), callChains);
            }
        }

        long endTime = System.currentTimeMillis();
        if (debug) System.out.println("调用链查找完成，耗时: " + (endTime - startTime) + "ms");

        return allCallChains;
    }


    /**
     * 使用缓存追踪从方法到REST控制器的调用链
     */
    private List<List<String>> traceCallChainFromCache(MethodIdentifier methodId, Set<String> visited) {
        String methodKey = getMethodKey(methodId.getClassName(), methodId.getMethodName());

        // 防止无限递归和循环依赖
        if (visited.contains(methodKey)) {
            return Collections.emptyList();
        }

        visited.add(methodKey);

        try {
            // 检查这是否已经是REST控制器方法
            if (restControllerMethods.contains(methodId.toString())) {
                if (debug) System.out.println("找到REST控制器方法: " + methodId);
                List<String> singleChain = new ArrayList<>();
                singleChain.add(methodId.toString());
                List<List<String>> result = new ArrayList<>();
                result.add(singleChain);
                return result;
            }

            // 从缓存中获取调用此方法的所有方法（调用者）
            Set<String> callers = methodCallers.getOrDefault(methodKey, new HashSet<>());

            if (debug) System.out.println("找到 " + callers.size() + " 个调用者: " + methodKey);

            List<List<String>> allChains = new ArrayList<>();

            // 对每个调用者，继续追踪
            for (String callerKey : callers) {
                // 获取调用者方法的完整信息
                String[] parts = callerKey.split("#");
                if (parts.length >= 2) {
                    String callerClassName = parts[0];
                    String callerMethodName = parts[1];

                    // 获取该类的所有同名方法（处理重载）
                    List<MethodDefinition> callerMethods = methodDefinitions.getOrDefault(callerKey, new ArrayList<>());
                    if (callerMethods.isEmpty()) {
                        // 如果没有精确匹配，尝试只匹配类名和方法名
                        for (Map.Entry<String, List<MethodDefinition>> entry : methodDefinitions.entrySet()) {
                            String key = entry.getKey();
                            if (key.startsWith(callerClassName + "#" + callerMethodName)) {
                                callerMethods = entry.getValue();
                                break;
                            }
                        }
                    }

                    for (MethodDefinition callerMethod : callerMethods) {
                        MethodIdentifier callerId = new MethodIdentifier(
                                callerMethod.getClassName(),
                                callerMethod.getMethodName(),
                                callerMethod.getMethodSignature(),
                                callerMethod.getFilePath()
                        );

                        if (debug) System.out.println("追踪调用者: " + callerId);
                        List<List<String>> chains = traceCallChainFromCache(callerId, new HashSet<>(visited)); // 传递副本
                        for (List<String> chain : chains) {
                            List<String> newChain = new ArrayList<>(chain);
                            newChain.add(methodId.toString());
                            allChains.add(newChain);
                        }
                    }
                }
            }

            return allChains;
        } finally {
            visited.remove(methodKey); // 确保在方法退出时清理visited集合
        }
    }



    /**
     * 检查方法是否使用了REST控制器注解
     */
    private boolean isRestControllerMethod(CompilationUnit cu, MethodDeclaration md) {
        // 获取包含此方法的类名
        Optional<ClassOrInterfaceDeclaration> classOpt = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (!classOpt.isPresent() || classOpt.get().isInterface()) {
            return false;
        }

        ClassOrInterfaceDeclaration classDecl = classOpt.get();
        String className = classDecl.getNameAsString();

        // 检查类是否有@RestController或@Controller注解
        boolean isControllerClass = classDecl.isAnnotationPresent("RestController") ||
                classDecl.isAnnotationPresent("Controller");

        // 检查类是否有@Component注解且实现了带有@RequestMapping的接口
        if (!isControllerClass) {
            // 检查是否实现了任何接口
            for (ClassOrInterfaceType implemented : classDecl.getImplementedTypes()) {
                String interfaceName = implemented.getNameAsString();
                if (hasRequestMappingInInterface(interfaceName)) {
                    isControllerClass = true;
                    break;
                }
            }
        }

        if (!isControllerClass) {
            return false;
        }

        // 检查方法是否有REST注解
        boolean hasMethodMapping = md.isAnnotationPresent("GetMapping") ||
                md.isAnnotationPresent("PostMapping") ||
                md.isAnnotationPresent("PutMapping") ||
                md.isAnnotationPresent("DeleteMapping") ||
                md.isAnnotationPresent("PatchMapping") ||
                md.isAnnotationPresent("RequestMapping");

        // 如果方法本身有映射注解，直接返回true
        if (hasMethodMapping) {
            return true;
        }

        // 检查方法是否实现了接口中的映射方法
        String methodName = md.getNameAsString();
        String methodSignature = getMethodSignature(md);

        // 检查实现的接口
        for (ClassOrInterfaceType implemented : classDecl.getImplementedTypes()) {
            String interfaceName = implemented.getNameAsString();
            Map<String, String> mappings = interfaceMethodMappings.get(interfaceName);
            if (mappings != null && mappings.containsKey(methodName + "#" + methodSignature)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查接口是否包含@RequestMapping注解
     */
    private boolean hasRequestMappingInInterface(String interfaceName) {
        String interfaceFilePath = classNameToFileMap.get(interfaceName);
        if (interfaceFilePath != null) {
            CompilationUnit interfaceCu = parsedFiles.get(interfaceFilePath);
            if (interfaceCu != null) {
                Optional<ClassOrInterfaceDeclaration> interfaceDecl = interfaceCu.findFirst(ClassOrInterfaceDeclaration.class);
                if (interfaceDecl.isPresent()) {
                    return interfaceDecl.get().isAnnotationPresent("RequestMapping");
                }
            }
        }
        return false;
    }


    /**
     * 提取类级别的@RequestMapping映射路径
     */
    private String extractClassLevelMapping(CompilationUnit cu, String className) {
        Optional<ClassOrInterfaceDeclaration> classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class);

        if (classDecl.isPresent()) {
            ClassOrInterfaceDeclaration cls = classDecl.get();

            // 检查类上的@RequestMapping注解
            String mapping = getMappingValue(cls, "RequestMapping");
            if (mapping != null) {
                return mapping;
            }

            // 如果是实现类，检查接口上的@RequestMapping注解
            if (!cls.isInterface()) {
                for (ClassOrInterfaceType implemented : cls.getImplementedTypes()) {
                    String interfaceName = implemented.getNameAsString();
                    String interfaceMapping = getInterfaceMapping(interfaceName);
                    if (interfaceMapping != null) {
                        return interfaceMapping;
                    }
                }
            }
        }

        return "";
    }

    /**
     * 获取接口上的@RequestMapping映射路径
     */
    private String getInterfaceMapping(String interfaceName) {
        // 查找接口文件
        String interfaceFilePath = classNameToFileMap.get(interfaceName);
        if (interfaceFilePath != null) {
            CompilationUnit interfaceCu = parsedFiles.get(interfaceFilePath);
            if (interfaceCu != null) {
                Optional<ClassOrInterfaceDeclaration> interfaceDecl = interfaceCu.findFirst(ClassOrInterfaceDeclaration.class);
                if (interfaceDecl.isPresent()) {
                    return getMappingValue(interfaceDecl.get(), "RequestMapping");
                }
            }
        }
        return null;
    }

    /**
     * 从方法或接口中提取方法级别的映射路径
     */
    private String extractMethodLevelMappingFromMethodOrInterface(CompilationUnit cu, MethodDeclaration md, String className) {
        // 首先尝试从方法本身提取
        String mapping = extractMethodLevelMapping(md);
        if (mapping != null) {
            return mapping;
        }

        // 如果方法本身没有映射，检查实现的接口
        Optional<ClassOrInterfaceDeclaration> classOpt = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (classOpt.isPresent() && !classOpt.get().isInterface()) {
            ClassOrInterfaceDeclaration classDecl = classOpt.get();
            String methodName = md.getNameAsString();
            String methodSignature = getMethodSignature(md);

            for (ClassOrInterfaceType implemented : classDecl.getImplementedTypes()) {
                String interfaceName = implemented.getNameAsString();
                Map<String, String> mappings = interfaceMethodMappings.get(interfaceName);
                if (mappings != null) {
                    String interfaceMapping = mappings.get(methodName + "#" + methodSignature);
                    if (interfaceMapping != null) {
                        return interfaceMapping;
                    }
                }
            }
        }

        return "";
    }


    /**
     * 获取方法签名字符串
     */
    private String getMethodSignature(MethodDeclaration md) {
        StringBuilder signature = new StringBuilder();
        signature.append(md.getNameAsString()).append("(");

        if (md.getParameters() != null) {
            for (int i = 0; i < md.getParameters().size(); i++) {
                if (i > 0) signature.append(",");
                signature.append(md.getParameters().get(i).getType().asString());
            }
        }

        signature.append(")");
        return signature.toString();
    }

    /**
     * 获取方法键值
     */
    private String getMethodKey(String className, String methodName) {
        return className + "#" + methodName;
    }

    /**
     * 获取Controller方法的完整URL路径
     *
     * @param methodIdentifier Controller方法的标识符
     * @return 完整的URL路径
     */
    public String getControllerMethodUrl(String methodIdentifier) {
        ControllerMethodInfo info = controllerMethodUrls.get(methodIdentifier);
        if (info != null) {
            return info.getFullUrl();
        }
        return "";
    }

    /**
     * 获取Controller方法的完整URL路径
     *
     * @param className       类名
     * @param methodName      方法名
     * @param methodSignature 方法签名
     * @return 完整的URL路径
     */
    public String getControllerMethodUrl(String className, String methodName, String methodSignature) {
        String methodKey = className + "#" + methodName + "#" + methodSignature;
        return getControllerMethodUrl(methodKey);
    }

    /**
     * 方法定义的表示
     */
    private static class MethodDefinition {
        private final String className;
        private final String methodName;
        private final String methodSignature;
        private final String filePath;

        public MethodDefinition(String className, String methodName, String methodSignature, String filePath) {
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.filePath = filePath;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getMethodSignature() {
            return methodSignature;
        }

        public String getFilePath() {
            return filePath;
        }

        @Override
        public String toString() {
            return className + "#" + methodName + "#" + methodSignature;
        }
    }

    /**
     * Java方法的简单表示，包含其签名
     */
    private static class MethodIdentifier {
        private final String className;
        private final String methodName;
        private final String methodSignature;
        private final String filePath;

        public MethodIdentifier(String className, String methodName, String methodSignature, String filePath) {
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.filePath = filePath;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getMethodSignature() {
            return methodSignature;
        }

        public String getFilePath() {
            return filePath;
        }

        @Override
        public String toString() {
            return className + "#" + methodName + "#" + methodSignature;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            MethodIdentifier that = (MethodIdentifier) obj;
            return Objects.equals(className, that.className) &&
                    Objects.equals(methodName, that.methodName) &&
                    Objects.equals(methodSignature, that.methodSignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(className, methodName, methodSignature);
        }
    }

    private final static class SearchType {
        public static final String MAPPING = "0";
        public static final String METHOD_CALL = "1";
        public static final String CONSTANT = "2";
    }
    /**
     * 存储Controller方法的URL映射信息
     */
    private static class ControllerMethodInfo {
        private final String className;
        private final String methodName;
        private final String methodSignature;
        private final String classLevelMapping;
        private final String methodLevelMapping;
        private final String filePath;

        public ControllerMethodInfo(String className, String methodName, String methodSignature,
                                    String classLevelMapping, String methodLevelMapping, String filePath) {
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.classLevelMapping = classLevelMapping;
            this.methodLevelMapping = methodLevelMapping;
            this.filePath = filePath;
        }

        // 获取完整URL路径
        public String getFullUrl() {
            String classPath = normalizePath(classLevelMapping);
            String methodPath = normalizePath(methodLevelMapping);

            // 合并类路径和方法路径
            if (classPath.isEmpty()) {
                return methodPath;
            } else if (methodPath.isEmpty()) {
                return classPath;
            } else {
                return classPath + methodPath;
            }
        }

        private String normalizePath(String path) {
            if (path == null || path.isEmpty() || path.equals("/")) {
                return "";
            }

            // 确保路径以/开头，不以/结尾
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path;
        }

        // getters...
        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getMethodSignature() {
            return methodSignature;
        }

        public String getClassLevelMapping() {
            return classLevelMapping;
        }

        public String getMethodLevelMapping() {
            return methodLevelMapping;
        }

        public String getFilePath() {
            return filePath;
        }
    }

}