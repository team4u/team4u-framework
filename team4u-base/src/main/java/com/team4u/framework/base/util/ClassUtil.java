package com.team4u.framework.base.util;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 类处理工具类
 * <p>
 * 提供类加载、简单值类型判断以及基于包路径的类扫描功能（支持文件系统和 Jar 包）。
 *
 * @author jay.wu
 */
public class ClassUtil {

    /**
     * 加载指定的类
     * <p>
     * 使用当前线程的上下文类加载器进行加载。
     *
     * @param className 类全限定名
     * @return 类对象
     * @throws RuntimeException 当类未找到时抛出
     */
    public static Class<?> loadClass(String className) {
        try {
            return Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + className, e);
        }
    }

    /**
     * 判断是否为简单值类型
     * <p>
     * 简单值类型被定义为：基本类型及其包装类、字符串、枚举、数字、日期、时间、URL、URI、本地化对象以及类对象本身。
     *
     * @param clazz 待判断的类
     * @return 如果是定义范围内的简单值类型则返回 true，否则返回 false
     */
    public static boolean isSimpleValueType(Class<?> clazz) {
        return (clazz.isPrimitive() ||
                clazz.equals(String.class) ||
                clazz.equals(Integer.class) ||
                clazz.equals(Long.class) ||
                clazz.equals(Double.class) ||
                clazz.equals(Float.class) ||
                clazz.equals(Boolean.class) ||
                clazz.equals(Character.class) ||
                clazz.equals(Byte.class) ||
                clazz.equals(Short.class) ||
                Enum.class.isAssignableFrom(clazz) ||
                Number.class.isAssignableFrom(clazz) ||
                Date.class.isAssignableFrom(clazz) ||
                Temporal.class.isAssignableFrom(clazz) ||
                URL.class.isAssignableFrom(clazz) ||
                URI.class.isAssignableFrom(clazz) ||
                Locale.class.isAssignableFrom(clazz) ||
                Class.class.isAssignableFrom(clazz));
    }

    /**
     * 扫描指定包及其子包下，指定父类或接口的所有子类
     * <p>
     * 遍历类路径，识别并加载符合继承关系的类。
     *
     * @param packageName 待扫描的包名
     * @param superClass  父类或接口类对象
     * @param <T>         父类泛型类型
     * @return 查找到的子类集合，不包含父类或接口本身
     */
    @SuppressWarnings("unchecked")
    public static <T> Set<Class<? extends T>> scanPackageBySuper(String packageName, Class<T> superClass) {
        Set<Class<? extends T>> classes = new HashSet<>();
        String packagePath = packageName.replace('.', '/');
        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(packagePath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();
                if ("file".equals(protocol)) {
                    String filePath = URLDecoder.decode(resource.getFile(), "UTF-8");
                    scanFile(classes, new File(filePath), packageName, superClass);
                } else if ("jar".equals(protocol)) {
                    scanJar(classes, resource, packagePath, superClass);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Scan package error", e);
        }
        return classes;
    }

    /**
     * 递归扫描文件目录下的类
     */
    private static <T> void scanFile(Set<Class<? extends T>> classes, File file, String packageName,
                                     Class<T> superClass) {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        scanFile(classes, f, packageName + "." + f.getName(), superClass);
                    } else if (f.getName().endsWith(".class")) {
                        String className = packageName + "." + f.getName().substring(0, f.getName().length() - 6);
                        addClass(classes, className, superClass);
                    }
                }
            }
        }
    }

    /**
     * 扫描 Jar 包中的类
     */
    private static <T> void scanJar(Set<Class<? extends T>> classes, URL url, String packagePath, Class<T> superClass)
            throws IOException {
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        JarFile jarFile = connection.getJarFile();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(packagePath) && name.endsWith(".class")) {
                String className = name.replace('/', '.').substring(0, name.length() - 6);
                addClass(classes, className, superClass);
            }
        }
    }

    /**
     * 加载类并检查是否匹配父类，匹配则加入结果集
     */
    @SuppressWarnings("unchecked")
    private static <T> void addClass(Set<Class<? extends T>> classes, String className, Class<T> superClass) {
        try {
            Class<?> clazz = loadClass(className);
            if (superClass.isAssignableFrom(clazz) && !superClass.equals(clazz)) {
                classes.add((Class<? extends T>) clazz);
            }
        } catch (Throwable ignore) {
            // 忽略加载失败的类
        }
    }
}
