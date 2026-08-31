package com.team4u.framework.flow;

import org.junit.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;

/**
 * 编译期类型安全与闭集防护验证：通过内联调用 javac，确认类型化 Flow 链在类型匹配时编译通过、
 * 类型不匹配时编译失败，并守护 Outcome、FlowResult、Gate、PersistentPolicy.Before/After 闭集不被外部继承。
 */
public class TypedCompilationTest {

    @Test
    public void javacAcceptsTypedChainAndRejectsWrongConnection() throws Exception {
        // 合法链：A: String→Integer 与 B: Integer→Long 类型匹配
        String valid = "import com.team4u.framework.flow.*;\n"
                + "import com.team4u.framework.flow.api.*;\n"
                + "import com.team4u.framework.flow.model.*;\n"
                + "class ValidFlow {\n"
                + "  static final class A implements Operation<String,Integer> {\n"
                + "    public Outcome<Integer> execute(OperationContext c, String i) {\n"
                + "      return Outcome.accepted(i.length());\n"
                + "    }\n"
                + "  }\n"
                + "  static final class B implements Operation<Integer,Long> {\n"
                + "    public Outcome<Long> execute(OperationContext c, Integer i) {\n"
                + "      return Outcome.accepted(i.longValue());\n"
                + "    }\n"
                + "  }\n"
                + "  Flow<String,Long> flow = Flow.step(A.class).then(B.class);\n"
                + "}\n";

        // 非法链：B 改为 Boolean→Long，与 A 的 Integer 输出不匹配，须编译失败
        String invalid = valid.replace("implements Operation<Integer,Long>",
                        "implements Operation<Boolean,Long>")
                .replace("Integer i", "Boolean i")
                .replace("i.longValue()", "1L");

        // 可复用子流：then 引用独立定义的 Flow<Integer,Long>
        String subflowValid = "import com.team4u.framework.flow.*;\n"
                + "import com.team4u.framework.flow.api.*;\n"
                + "import com.team4u.framework.flow.model.*;\n"
                + "class ValidSubflow {\n"
                + "  static final Flow<Integer,Long> reusable = Flow.step(\n"
                + "    (Operation<Integer,Long>) (c, i) -> Outcome.accepted(i.longValue()));\n"
                + "  Flow<String,Long> flow = Flow.step(\n"
                + "    (Operation<String,Integer>) (c, i) -> Outcome.accepted(i.length()))\n"
                + "    .then(reusable);\n"
                + "}\n";

        // 子流接入点类型不符（Boolean 而非 String）须编译失败
        String subflowInvalid = subflowValid.replace(
                "Flow<String,Long> flow", "Flow<Boolean,Long> flow");

        // thenOptional 仅允许同类型 Operation<T,T> / Flow<T,T>
        String optionalValid = "import com.team4u.framework.flow.*;\n"
                + "import com.team4u.framework.flow.api.*;\n"
                + "import com.team4u.framework.flow.model.*;\n"
                + "class ValidOptional {\n"
                + "  static final class Same implements Operation<String,String> {\n"
                + "    public Outcome<String> execute(OperationContext c, String i) {\n"
                + "      return Outcome.skipped(Reason.of(\"NA\", \"na\"));\n"
                + "    }\n"
                + "  }\n"
                + "  static final Flow<String,String> reusable = Flow.step(Same.class);\n"
                + "  Flow<String,String> byInstance = Flow.<String>identity().thenOptional(new Same());\n"
                + "  Flow<String,String> byClass = Flow.<String>identity().thenOptional(Same.class, \"same\");\n"
                + "  Flow<String,String> byFlow = Flow.<String>identity().thenOptional(reusable);\n"
                + "}\n";

        String optionalOperationInvalid = "import com.team4u.framework.flow.*;\n"
                + "import com.team4u.framework.flow.api.*;\n"
                + "import com.team4u.framework.flow.model.*;\n"
                + "class InvalidOptionalOperation {\n"
                + "  static final class Change implements Operation<String,Integer> {\n"
                + "    public Outcome<Integer> execute(OperationContext c, String i) {\n"
                + "      return Outcome.accepted(i.length());\n"
                + "    }\n"
                + "  }\n"
                + "  Flow<String,String> flow = Flow.<String>identity().thenOptional(Change.class);\n"
                + "}\n";

        String optionalFlowInvalid = "import com.team4u.framework.flow.*;\n"
                + "import com.team4u.framework.flow.api.*;\n"
                + "import com.team4u.framework.flow.model.*;\n"
                + "class InvalidOptionalFlow {\n"
                + "  static final Flow<String,Integer> change = Flow.step(\n"
                + "    (Operation<String,Integer>) (c, i) -> Outcome.accepted(i.length()));\n"
                + "  Flow<String,String> flow = Flow.<String>identity().thenOptional(change);\n"
                + "}\n";

        // 闭集守护 1：外部类不得直接继承 Outcome
        String extendOutcome = "package custom.external;\n"
                + "import com.team4u.framework.flow.model.Outcome;\n"
                + "class CustomOutcome<T> extends Outcome<T> {\n"
                + "}\n";

        // 闭集守护 2：外部类不得直接继承 FlowResult
        String extendFlowResult = "package custom.external;\n"
                + "import com.team4u.framework.flow.model.FlowResult;\n"
                + "class CustomFlowResult<T> extends FlowResult<T> {\n"
                + "}\n";

        // 闭集守护 3：外部类不得直接继承 Gate
        String extendGate = "package custom.external;\n"
                + "import com.team4u.framework.flow.api.Gate;\n"
                + "class CustomGate extends Gate {\n"
                + "}\n";

        // 闭集守护 4：外部类不得直接继承 PersistentPolicy.Before
        String extendPolicyBefore = "package custom.external;\n"
                + "import com.team4u.framework.flow.api.PersistentPolicy;\n"
                + "class CustomBefore<S> extends PersistentPolicy.Before<S> {\n"
                + "}\n";

        assertEquals(0, compile("ValidFlow", valid));
        assertNotEquals(0, compile("ValidFlow", invalid));
        assertEquals(0, compile("ValidSubflow", subflowValid));
        assertNotEquals(0, compile("ValidSubflow", subflowInvalid));
        assertEquals(0, compile("ValidOptional", optionalValid));
        assertNotEquals(0, compile("InvalidOptionalOperation", optionalOperationInvalid));
        assertNotEquals(0, compile("InvalidOptionalFlow", optionalFlowInvalid));

        assertNotEquals(0, compile("CustomOutcome", extendOutcome));
        assertNotEquals(0, compile("CustomFlowResult", extendFlowResult));
        assertNotEquals(0, compile("CustomGate", extendGate));
        assertNotEquals(0, compile("CustomBefore", extendPolicyBefore));
    }

    private static int compile(String name, String source) throws Exception {
        Path directory = Files.createTempDirectory("team4u-flow-compile-");
        try {
            Path file = directory.resolve(name + ".java");
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                out.write(source.getBytes(StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            return ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", directory.toString(), file.toString());
        } finally {
            try {
                File dir = directory.toFile();
                deleteDir(dir);
            } catch (Exception ignored) { }
        }
    }

    private static void deleteDir(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        file.delete();
    }
}
