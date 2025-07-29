package io.github.gekkoz.callchain.core.descriptor;

/**
 * 存储Controller方法的URL映射信息
 */
public class ControllerMethodDescriptor {
    private final String className;
    private final String methodName;
    private final String methodSignature;
    private final String classLevelMapping;
    private final String methodLevelMapping;
    private final String filePath;

    public ControllerMethodDescriptor(String className, String methodName, String methodSignature,
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
