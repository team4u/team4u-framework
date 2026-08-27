import java.io.File
import java.util.ArrayList
import java.util.List

File treeFile = new File(basedir, "target/consumer-dependency-runtime.tree")
if (!treeFile.isFile() || treeFile.length() == 0L) {
    throw new AssertionError("Runtime dependency tree is missing or empty: " + treeFile)
}

List<String> banned = new ArrayList()
treeFile.eachLine { line ->
    if (line.contains("org.springframework") ||
            line.contains("com.fasterxml.jackson") ||
            line.contains("net.bytebuddy")) {
        banned.add(line.trim())
    }
}

if (!banned.isEmpty()) {
    throw new AssertionError("Minimal consumer runtime tree leaks banned dependencies:\n" + banned.join("\n"))
}
