package com.team4u.framework.flow.durable.boundary;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.durable.engine.DurablePlanNode;
import com.team4u.framework.flow.spi.ExecutableFlowVisitor;

/**
 * 组9（源码扫描）：断言 Durable 生产源码不依赖 Core 内部投影类型
 * （CoreDurableBridge/PlanNode/Logical）、不使用 java.io.Serializable 与反射。
 * Durable 只允许通过 Core 公开投影 SPI（ExecutableFlowVisitor 等）访问计划结构。
 */
public class DurableSourceBoundaryTest {

    /** 禁止出现的标识符（词边界匹配，避免误伤 DurablePlanNode 等本地类型）。 */
    private static final String[] FORBIDDEN = {
            "CoreDurableBridge",
            "\\bPlanNode\\b",
            "\\bLogical\\b",
            "java\\.io\\.Serializable",
            "\\breflect\\b"
    };

    @Test
    public void productionSourcesStayWithinPublicCoreApi() throws IOException {
        Path moduleRoot = locateModuleRoot();
        final List<Path> javaFiles = new ArrayList<Path>();
        Files.walkFileTree(moduleRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if ("target".equals(name) || "test".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // 21 个生产文件都必须被扫描
        assertTrue("生产源码文件数异常: " + javaFiles.size(), javaFiles.size() >= 21);
        for (Path file : javaFiles) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN) {
                Pattern pattern = Pattern.compile(forbidden);
                assertFalse("生产文件不得引用受限标识符 [" + forbidden + "]: "
                                + file.getFileName(),
                        pattern.matcher(source).find());
            }
        }
    }

    /** 从工作目录向上定位 modules/flow/durable。 */
    private static Path locateModuleRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("modules/flow/durable/src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate modules/flow/durable from "
                + Paths.get("").toAbsolutePath());
    }
}
