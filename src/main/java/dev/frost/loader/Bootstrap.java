package dev.frost.loader;

import java.lang.reflect.Method;

public class Bootstrap {
    private static final String REAL_MAIN = "REAL_MAIN_PLACEHOLDER";

    public static void main(String[] args) throws Throwable {
        DecryptedClassLoader classLoader = new DecryptedClassLoader(Bootstrap.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(classLoader);
        Class<?> mainClass = classLoader.loadClass(REAL_MAIN);
        Method mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }
}
