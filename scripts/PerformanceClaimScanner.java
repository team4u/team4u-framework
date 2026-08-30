import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic scanner for unsupported absolute performance claims.
 *
 * Why a Java scanner instead of grep: the claim language mixes CJK quantifier
 * words with ASCII keywords, and byte-oriented tools change behavior with the
 * invoking locale (bracket expressions and case folding differ between
 * zh_CN.UTF-8, en_US.UTF-8, and C). This scanner decodes every file as UTF-8
 * explicitly, uses Java regular expressions whose matching is locale
 * independent, and folds case for ASCII keywords only, so the result is
 * identical no matter which environment invokes the gate.
 *
 * Model: each line is split into subclauses at Chinese and ASCII punctuation
 * (commas, periods, semicolons, colons, enumeration commas, question and
 * exclamation marks, pipes, brackets, and quotes). A quantifier and the noun
 * it quantifies must sit in the same subclause, so pairs separated by clause
 * punctuation ("无锁、低分配" style lists) are not claims.
 *
 * Chinese families:
 *   1. (零|无) ... cost noun: GC (ASCII right boundary, CJK suffix such as
 *      开销 allowed), 分配 (covers 内存分配), 对象 (covers 对象创建), 创建,
 *      加锁, and any X 开销/消耗 prefix form (零正则开销, 零性能消耗).
 *   2. 零 ... mechanism noun (远程|序列化|加载|正则|重试|反射) alone is a
 *      claim; the 无 form ("uses no regex") is the approved mechanism
 *      phrasing and stays allowed unless a cost noun follows in the same
 *      subclause.
 *   3. digit 0 with the same cost nouns; the digit must not be preceded by an
 *      ASCII alphanumeric or dot, so 10GC, 1.0GC, and x0GC stay negative, and
 *      GCC is not a keyword for the digit form (0 GCC / 0GCC negative).
 *   4. 彻底(消除|避免|杜绝) ... cost noun in one subclause.
 *   5. 绝对 ... (无锁|零|性能|锁|cost noun) in one subclause.
 *   6. 纳秒级 and 杜绝频繁 GC.
 *
 * English families (ASCII word boundaries on both sides, case-insensitive):
 *   zero/no/0 + up to three tokens + GC, garbage collection(s), allocation(s),
 *   object creation, objects, overhead(s); metric + separated free
 *   (GC-free, allocation-free, object-creation-free, overhead-free); and
 *   nanosecond-level. `zero get calls`, `low overhead`, `gc.alloc.*`, and
 *   TimeUnit.NANOSECONDS do not match any family.
 *
 * Approved mechanisms that stay negative: 无锁 alone, 无 BigDecimal, 无反射
 * and 无正则 alone (compound 无-idioms such as 无法/无需/无缝/无用 are rejected
 * by prefix), zero get calls, low overhead, gc.alloc metrics, and
 * TimeUnit.NANOSECONDS. When a guard rejects a candidate, the search
 * restarts right after the rejected quantifier instead of after the whole
 * match, so claims embedded after an allowed compound (`无锁零GC`) are
 * still found; the restart position strictly advances every iteration.
 *
 * The GC keyword families are case-insensitive (lowercase gc claims);
 * gc.alloc metrics stay safe because every Chinese/digit family requires a
 * leading quantifier (零/无/0) before the noun.
 *
 * CLI: `--self-test` runs the built-in static corpus (positive claims that
 * must be rejected and negative text that must stay allowed) with no file
 * dependencies, printing one line per mismatch and exiting non-zero; any
 * other single argument is the repository root. Scans the root README.md,
 * benchmarks/README.md, every .md/.markdown/.html/.htm
 * (case-insensitive) under docs/ whose path has no superpowers segment, and
 * every main Java source (path segment sequence src/main/java) outside
 * target/, .git/, and .worktrees/ (this helper is never scanned).
 * At least one Java source must be found, otherwise the gate is RED. Every
 * hit is printed as path:line:text to stderr and the exit code is 1; exit 0
 * means no unsupported claim was found.
 *
 * Java 8 source only.
 */
public final class PerformanceClaimScanner {

    private PerformanceClaimScanner() {
    }

    /** Subclause boundary punctuation, Chinese and ASCII. */
    private static final String BOUNDARY =
            ",.;:?!|()[]{}<>\"'"
                    + "，。；：？！、（）「」『』【】《》“”‘’…";

    /** Any character that is not a clause boundary; whitespace is allowed. */
    private static final String GAP = "[^" + regexClass(BOUNDARY) + "]";

    private static final String COST_NOUN =
            "(?:GC(?![A-Za-z])|分配|对象|创建|加锁|开销|消耗)";
    private static final String MECH_NOUN =
            "(?:远程|序列化|加载|正则|重试|反射)";

    private static final Pattern CHINESE_COST =
            Pattern.compile("[零无]" + GAP + "{0,14}" + COST_NOUN,
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_MECH =
            Pattern.compile("零" + GAP + "{0,8}" + MECH_NOUN);
    private static final Pattern DIGIT_COST =
            Pattern.compile("(?<![A-Za-z0-9.])0" + GAP + "{0,6}" + COST_NOUN,
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern THOROUGH =
            Pattern.compile("彻底(?:消除|避免|杜绝)" + GAP + "{0,20}" + COST_NOUN);
    private static final Pattern ABSOLUTE =
            Pattern.compile("绝对" + GAP + "{0,10}"
                    + "(?:无锁|零|性能|锁|分配|对象|创建|加锁|开销|消耗|GC(?![A-Za-z]))",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NANOS_CN = Pattern.compile("纳秒级");
    private static final Pattern FORBID_FREQUENT_GC =
            Pattern.compile("杜绝\\s{0,4}频繁\\s{0,4}GC", Pattern.CASE_INSENSITIVE);

    private static final String EN_NOUN =
            "(?:garbage(?:[\\s_-]{1,2}collections?)?|object\\s+creations?|allocations?|objects?|overheads?|GC)";
    private static final String EN_TOKENS = "(?:[\\s-]+[A-Za-z][A-Za-z-]*){0,3}";
    private static final Pattern EN_QUANT = Pattern.compile(
            "\\b(?:zero|no)\\b" + EN_TOKENS + "[\\s-]+" + EN_NOUN + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EN_DIGIT = Pattern.compile(
            "(?<![A-Za-z0-9.])0" + EN_TOKENS + "[\\s-]+" + EN_NOUN + "\\b");
    private static final Pattern EN_FREE = Pattern.compile(
            "\\b(?:GC|garbage(?:[\\s_-]{1,2}collection)?|alloc(?:ation)?|object(?:[\\s_-]{1,2}creations?)?|overhead)"
                    + "[\\s_-]{0,2}free\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EN_NANOS = Pattern.compile(
            "\\bnanoseconds?[\\s_-]{0,2}level\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Characters that turn 无 into a non-claim compound idiom when they
     * directly follow it (无法, 无需, 无缝, 无用, 无锁, ...). A quantifier
     * followed by one of these is never the head of an absolute claim.
     */
    private static final String WU_COMPOUND_NEXT =
            "法须需谓用关害损效意碍妨论限序缝锁参主值感侵依状配外知疑断停染";

    /** Characters that turn 零 into a non-quantifier idiom when they precede it. */
    private static final String ZERO_PREV = "从归清";

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            System.exit(selfTest());
        }
        if (args.length != 1) {
            System.err.println("usage: PerformanceClaimScanner <repository-root>"
                    + System.getProperty("line.separator")
                    + "       PerformanceClaimScanner --self-test");
            System.exit(2);
        }
        File root = new File(args[0]).getAbsoluteFile();
        if (!root.isDirectory()) {
            System.err.println("not a directory: " + root);
            System.exit(2);
        }

        List<File> targets = new ArrayList<File>();
        addIfFile(new File(root, "README.md"), targets);
        addIfFile(new File(root, "benchmarks" + File.separator + "README.md"), targets);
        File docs = new File(root, "docs");
        if (docs.isDirectory()) {
            collectMarkdown(docs, targets);
        }

        List<File> javaSources = new ArrayList<File>();
        collectJavaSources(root, javaSources);
        if (javaSources.isEmpty()) {
            System.err.println("performance claims gate: RED (no */src/main/java sources found to scan)");
            System.exit(1);
        }
        targets.addAll(javaSources);

        int hits = 0;
        for (File target : targets) {
            hits += scan(target, root);
        }
        if (hits > 0) {
            System.err.println("unsupported absolute performance claim(s): " + hits + " line(s) listed above");
            System.err.println("performance claims gate: RED");
            System.exit(1);
        }
        System.out.println("scanned " + (targets.size() - javaSources.size())
                + " documentation files and " + javaSources.size() + " java sources");
    }

    /**
     * One self-test sample: expected true means the line must be rejected as
     * an unsupported claim, expected false means it must stay allowed.
     */
    private static final class Sample {
        final String family;
        final String text;
        final boolean expected;

        Sample(String family, String text, boolean expected) {
            this.family = family;
            this.text = text;
            this.expected = expected;
        }
    }

    /** Built-in corpus: positive claims that must RED, negatives that must stay GREEN. */
    private static final Sample[] SELF_TEST_SAMPLES = {
        // --- Chinese quantifier + cost noun, exact and lowercase-keyword forms ---
        new Sample("cn-quant", "零 GC", true),
        new Sample("cn-quant", "零gc", true),
        new Sample("cn-quant", "无 GC", true),
        new Sample("cn-quant", "无gc", true),
        new Sample("cn-quant", "0 GC", true),
        new Sample("cn-quant", "0gc", true),
        new Sample("cn-quant", "无锁零GC", true),
        new Sample("cn-quant", "无锁零分配", true),
        // --- Chinese idioms ---
        new Sample("cn-idiom", "纳秒级", true),
        new Sample("cn-idiom", "杜绝频繁 GC", true),
        new Sample("cn-idiom", "杜绝频繁GC", true),
        new Sample("cn-idiom", "彻底消除 GC 开销", true),
        new Sample("cn-idiom", "绝对无锁", true),
        new Sample("cn-idiom", "零开销", true),
        new Sample("cn-idiom", "零对象创建", true),
        new Sample("cn-idiom", "0 对象创建", true),
        new Sample("cn-idiom", "零分配", true),
        new Sample("cn-idiom", "无分配", true),
        new Sample("cn-idiom", "零正则开销", true),
        // --- English quantifier + noun ---
        new Sample("en-quant", "zero GC", true),
        new Sample("en-quant", "no GC", true),
        new Sample("en-quant", "0 GC", true),
        new Sample("en-quant", "zero allocation", true),
        new Sample("en-quant", "no allocations", true),
        new Sample("en-quant", "zero object creation", true),
        new Sample("en-quant", "zero-object creation", true),
        new Sample("en-quant", "zero overhead", true),
        new Sample("en-quant", "no overheads", true),
        // --- English metric-free families ---
        new Sample("en-free", "GC free", true),
        new Sample("en-free", "GC-free", true),
        new Sample("en-free", "allocation free", true),
        new Sample("en-free", "allocation-free", true),
        new Sample("en-free", "alloc free", true),
        new Sample("en-free", "alloc-free", true),
        new Sample("en-free", "garbage-free", true),
        new Sample("en-free", "garbage collection free", true),
        new Sample("en-free", "garbage-collection-free", true),
        new Sample("en-free", "object creation free", true),
        new Sample("en-free", "object-creation-free", true),
        new Sample("en-free", "overhead-free", true),
        // --- English nanosecond family ---
        new Sample("en-nanos", "nanosecond-level", true),
        new Sample("en-nanos", "nanoseconds level", true),
        // --- review case: quantifier not on a banned noun ---
        new Sample("en-quant", "no reflection overhead", true),
        // --- negatives: benign metric/identifier/idiom text that must stay allowed ---
        new Sample("neg-digit-boundary", "0 GCC", false),
        new Sample("neg-digit-boundary", "0GCC", false),
        new Sample("neg-digit-boundary", "x0GC", false),
        new Sample("neg-digit-boundary", "10GC", false),
        new Sample("neg-digit-boundary", "1.0GC", false),
        new Sample("neg-digit-boundary", "compiler GCC", false),
        new Sample("neg-free-suffix", "GC-freeware", false),
        new Sample("neg-free-suffix", "GC freelance", false),
        new Sample("neg-noun", "no BigDecimal", false),
        new Sample("neg-noun", "no lock", false),
        new Sample("neg-noun", "zero get calls", false),
        new Sample("neg-noun", "no reflection needed", false),
        new Sample("neg-term", "zero-copy", false),
        new Sample("neg-term", "gc.alloc.rate.norm", false),
        new Sample("neg-term", "TimeUnit.NANOSECONDS", false),
        new Sample("neg-subclause", "零配置、低开销", false),
        new Sample("neg-subclause", "无锁、低分配", false),
        new Sample("neg-subclause", "低开销", false),
        new Sample("neg-subclause", "低分配", false),
        new Sample("neg-subclause", "无锁", false),
        new Sample("neg-subclause", "无 BigDecimal", false),
        new Sample("neg-subclause", "无反射", false),
        new Sample("neg-subclause", "无正则", false),
        new Sample("neg-idiom", "零依赖", false),
        new Sample("neg-idiom", "零侵入", false),
        new Sample("neg-idiom", "零配置", false),
        new Sample("neg-idiom", "零 NPE 空对象", false),
        new Sample("neg-idiom", "从零开始", false),
        new Sample("neg-idiom", "无法匹配", false),
        new Sample("neg-idiom", "无需重试", false),
        new Sample("neg-metric", "allocation rate", false),
        new Sample("neg-metric", "JDK 10 GC", false),
        new Sample("neg-metric", "nanoseconds elapsed", false),
        new Sample("neg-metric", "millisecond level", false),
    };

    /**
     * Built-in corpus runner. Returns the process exit code: 0 when every
     * sample matches its expectation, 1 otherwise. Mismatches print the
     * case, the expected verdict, and the sample text.
     */
    private static int selfTest() {
        int positives = 0;
        int negatives = 0;
        int mismatches = 0;
        for (Sample sample : SELF_TEST_SAMPLES) {
            if (sample.expected) {
                positives++;
            } else {
                negatives++;
            }
            boolean actual = lineClaims(sample.text);
            if (actual != sample.expected) {
                mismatches++;
                System.err.println("self-test mismatch [" + sample.family + "] "
                        + (sample.expected ? "expected claim, allowed" : "expected allowed, claimed")
                        + ": " + sample.text);
            }
        }
        if (mismatches > 0) {
            System.err.println("self-test: " + mismatches + " mismatch(es), " + positives
                    + " positive and " + negatives + " negative cases");
            return 1;
        }
        System.out.println("self-test: " + positives + " positive and " + negatives
                + " negative cases, all as expected");
        return 0;
    }

    private static int scan(File file, File root) {
        String rel = file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
        int hits = 0;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            int lineNo = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineClaims(line)) {
                    System.err.println(rel + ":" + lineNo + ":" + line.trim());
                    hits++;
                }
            }
        } catch (IOException e) {
            System.err.println(rel + ":0:cannot read file: " + e.getMessage());
            hits++;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException suppressed) {
                    // ignored
                }
            }
        }
        return hits;
    }

    private static boolean lineClaims(String line) {
        int length = line.length();
        int start = -1;
        for (int i = 0; i <= length; i++) {
            char c = i < length ? line.charAt(i) : '\0';
            boolean boundary = i == length || BOUNDARY.indexOf(c) >= 0;
            if (!boundary && start < 0) {
                start = i;
            } else if (boundary && start >= 0) {
                // Check the subclause as a region of the original line with
                // transparent bounds, so lookbehinds and word boundaries keep
                // seeing the real preceding character across the split:
                // `1.0GC` splits into `1` and `0GC`, and the digit guard must
                // still observe the dot before the 0.
                if (regionClaims(line, start, i)) {
                    return true;
                }
                start = -1;
            }
        }
        return false;
    }

    private static boolean regionClaims(String line, int start, int end) {
        if (findClaimRegion(CHINESE_COST, line, start, end)
                || findClaimRegion(CHINESE_MECH, line, start, end)
                || findClaimRegion(DIGIT_COST, line, start, end)
                || findClaimRegion(THOROUGH, line, start, end)
                || findClaimRegion(ABSOLUTE, line, start, end)
                || findRegion(NANOS_CN, line, start, end)
                || findRegion(FORBID_FREQUENT_GC, line, start, end)
                || findRegion(EN_QUANT, line, start, end)
                || findRegion(EN_DIGIT, line, start, end)
                || findRegion(EN_FREE, line, start, end)
                || findRegion(EN_NANOS, line, start, end)) {
            return true;
        }
        return false;
    }

    private static boolean findRegion(Pattern family, String line, int start, int end) {
        Matcher m = family.matcher(line).region(start, end);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        return m.find();
    }

    /**
     * Runs one Chinese family over a subclause region of the original line,
     * applying the 无-compound and 零-idiom guards to each candidate match
     * before declaring a claim. The region keeps transparent bounds so the
     * digit guard still sees the character before the subclause. When a
     * guard rejects a candidate, the search restarts right after the rejected
     * quantifier codepoint rather than skipping the whole match, so a claim
     * embedded after an allowed compound head (`无锁零GC`) is still found;
     * every restart position strictly advances, so there is no infinite
     * loop, and the digit head never restarts (no transparent-bounds digit
     * regressions).
     */
    private static boolean findClaimRegion(Pattern family, String line, int start, int end) {
        Matcher m = family.matcher(line).region(start, end);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        int floor = start;
        while (m.find()) {
            String match = m.group();
            // 零 NPE 空对象 is a correctness pattern name, not a claim.
            if (match.contains("NPE")) {
                floor = Math.max(floor, m.start() + 1);
                m.region(floor, end);
                continue;
            }
            char head = match.charAt(0);
            if (head == '0' || head != '零' && head != '无') {
                return true;
            }
            int restart = m.start() + 1;
            if (head == '无' && restart < line.length()
                    && WU_COMPOUND_NEXT.indexOf(line.charAt(restart)) >= 0) {
                floor = Math.max(floor, restart);
                m.region(floor, end);
                continue;
            }
            if (head == '零' && m.start() > 0
                    && ZERO_PREV.indexOf(line.charAt(m.start() - 1)) >= 0) {
                floor = Math.max(floor, restart);
                m.region(floor, end);
                continue;
            }
            return true;
        }
        return false;
    }

    private static void addIfFile(File file, List<File> out) {
        if (file.isFile()) {
            out.add(file);
        }
    }

    private static void collectMarkdown(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children);
        for (File child : children) {
            String name = child.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (child.isDirectory()) {
                if (isSkippedDir(name)) {
                    continue;
                }
                collectMarkdown(child, out);
            } else if (lower.endsWith(".md") || lower.endsWith(".markdown")
                    || lower.endsWith(".html") || lower.endsWith(".htm")) {
                out.add(child);
            }
        }
    }

    private static void collectJavaSources(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children);
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                if (isSkippedDir(name)) {
                    continue;
                }
                collectJavaSources(child, out);
            } else if (name.endsWith(".java") && isMainSource(child)) {
                if ("PerformanceClaimScanner.java".equals(name)) {
                    continue;
                }
                out.add(child);
            }
        }
    }

    private static boolean isSkippedDir(String name) {
        return "target".equals(name) || ".git".equals(name) || ".worktrees".equals(name)
                || "superpowers".equals(name);
    }

    private static boolean isMainSource(File file) {
        File dir = file.getParentFile();
        if (dir == null) {
            return false;
        }
        // Walk up the package directories until the directory named java.
        while (!"java".equals(dir.getName())) {
            dir = dir.getParentFile();
            if (dir == null) {
                return false;
            }
        }
        File mainDir = dir.getParentFile();
        if (mainDir == null || !"main".equals(mainDir.getName())) {
            return false;
        }
        File srcDir = mainDir.getParentFile();
        return srcDir != null && "src".equals(srcDir.getName());
    }

    private static String regexClass(String chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            if (c == ']' || c == '[' || c == '\\' || c == '^' || c == '-') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
