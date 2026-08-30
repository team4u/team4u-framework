package com.team4u.framework.fsm;

import org.junit.Assert;
import org.junit.Test;

/**
 * Mermaid 状态图渲染测试：别名声明、通配、自环、守卫标签与转义。
 */
public class StateMachineMermaidTest {

    private enum State { A, B, C }

    private enum Event { E1, E2 }

    @Test
    public void testRenderSimpleMachine() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("simple", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .from(State.B).on(Event.E2).to(State.C)
                .build();

        String diagram = StateMachineMermaid.render(machine);

        String expected = "stateDiagram-v2\n"
                + "    [*] --> s0\n"
                + "    state \"A\" as s0\n"
                + "    state \"B\" as s1\n"
                + "    state \"C\" as s2\n"
                + "    s0 --> s1 : E1\n"
                + "    s1 --> s2 : E2\n";

        Assert.assertEquals(expected, diagram);
    }

    @Test
    public void testRenderAnySourceAnyEventStayAndGuard() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("wild", State.A)
                .from(State.A).onAny().when("not shipped", ctx -> true).to(State.B)
                .fromAny().on(Event.E2).to(State.C)
                .fromAny().onAny().stay().named("global-stay")
                .build();

        String diagram = StateMachineMermaid.render(machine);

        String expected = "stateDiagram-v2\n"
                + "    [*] --> s0\n"
                + "    state \"A\" as s0\n"
                + "    state \"B\" as s1\n"
                + "    state \"C\" as s2\n"
                + "    state \"*(any state)\" as any_state\n"
                + "    s0 --> s1 : *(any event) [not shipped]\n"
                + "    any_state --> s2 : E2\n"
                + "    any_state --> any_state : *(any event)\n";

        Assert.assertEquals(expected, diagram);
    }

    @Test
    public void testRenderEscapesSpecialCharacters() {
        // toString 含引号、换行、回车的自定义状态与事件
        StateMachine<String, String, Void> machine = StateMachine
                .<String, String, Void>builder("escape", "be\"fore")
                .from("be\"fore").on("line1\nline2").to("after\r\nnext")
                .build();

        String diagram = StateMachineMermaid.render(machine);

        String expected = "stateDiagram-v2\n"
                + "    [*] --> s0\n"
                + "    state \"be#quot;fore\" as s0\n"
                + "    state \"after next\" as s1\n"
                + "    s0 --> s1 : line1 line2\n";

        Assert.assertEquals(expected, diagram);
    }

    @Test
    public void testRenderGuardDescriptionIsEscaped() {
        StateMachine<String, String, Void> machine = StateMachine
                .<String, String, Void>builder("escape-guard", "A")
                .from("A").on("E").when("say \"hi\"\nplease", ctx -> true).to("B")
                .build();

        String diagram = StateMachineMermaid.render(machine);

        Assert.assertTrue(diagram.contains("s0 --> s1 : E [say #quot;hi#quot; please]"));
    }

    @Test
    public void testRenderEscapesSemicolonAndHash() {
        // 分号会被 Mermaid 解析为语句分隔符、井号是实体引导符，必须转义为实体；
        // 状态、事件与守卫描述三类文本都要经过同一套编码
        StateMachine<String, String, Void> machine = StateMachine
                .<String, String, Void>builder("escape-semicolon", "draft;v2")
                .from("draft;v2").on("approve#now")
                    .when("amount > 100;currency == #CNY#", ctx -> true)
                    .to("done;final#1")
                .build();

        String diagram = StateMachineMermaid.render(machine);

        String expected = "stateDiagram-v2\n"
                + "    [*] --> s0\n"
                + "    state \"draft#59;v2\" as s0\n"
                + "    state \"done#59;final#35;1\" as s1\n"
                + "    s0 --> s1 : approve#35;now [amount > 100#59;currency == #35;CNY#35;]\n";

        Assert.assertEquals(expected, diagram);
    }

    @Test
    public void testRenderEntityIntroducedByEscapingIsNotDoubleEncoded() {
        // 已是实体形状的字面量（#59;）不会被二次编码：并号只转成 #35; 一次，
        // 编码产物本身保持稳定（重复渲染结果一致，且不含未被转义的内层实体边界）
        StateMachine<String, String, Void> machine = StateMachine
                .<String, String, Void>builder("no-double", "a#59;b")
                .from("a#59;b").on("#quot;").to("c")
                .build();

        String first = StateMachineMermaid.render(machine);
        String second = StateMachineMermaid.render(machine);

        Assert.assertEquals(first, second);
        // 字面量 "a#59;b"：并号转成 #35;、原有分号转成 #59;，产物不会被二次编码
        Assert.assertTrue(first.contains("state \"a#35;59#59;b\" as s0"));
        // 事件字面量 "#quot;"：并号只转一次 #35;，其余字符原样保留
        Assert.assertTrue(first.contains(" : #35;quot#59;\n"));
    }

    @Test
    public void testRenderIsStableAcrossCalls() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("stable", State.A)
                .fromAny().on(Event.E1).to(State.B)
                .from(State.B).on(Event.E2).stay()
                .build();

        Assert.assertEquals(StateMachineMermaid.render(machine), StateMachineMermaid.render(machine));
    }

    @Test
    public void testRenderStateOrderFollowsFirstAppearance() {
        // 状态按首次出现顺序编号：初始状态最先，其后按声明顺序
        StateMachine<String, String, Void> machine = StateMachine
                .<String, String, Void>builder("order", "START")
                .from("START").on("go").to("MIDDLE")
                .from("MIDDLE").on("back").to("START")
                .from("START").on("jump").to("END")
                .build();

        String diagram = StateMachineMermaid.render(machine);

        Assert.assertTrue(diagram.contains("state \"START\" as s0"));
        Assert.assertTrue(diagram.contains("state \"MIDDLE\" as s1"));
        Assert.assertTrue(diagram.contains("state \"END\" as s2"));
        int start = diagram.indexOf("state \"START\"");
        int middle = diagram.indexOf("state \"MIDDLE\"");
        int end = diagram.indexOf("state \"END\"");
        Assert.assertTrue(start >= 0 && middle > start && end > middle);
    }

    @Test
    public void testRenderRejectsNullMachine() {
        try {
            StateMachineMermaid.render(null);
            Assert.fail("null 状态机应抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
