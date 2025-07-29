package io.github.gekkoz.callchain.core.descriptor;

import java.util.Objects;

/**
 * Java方法的简单表示，包含其签名
 */
public class MethodDescriptor {
    private final String className;
    private final String methodName;
    private final String methodSignature;
    private final String filePath;

    public MethodDescriptor(String className, String methodName, String methodSignature, String filePath) {
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
        MethodDescriptor that = (MethodDescriptor) obj;
        return Objects.equals(className, that.className) &&
                Objects.equals(methodName, that.methodName) &&
                Objects.equals(methodSignature, that.methodSignature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, methodSignature);
    }
}
