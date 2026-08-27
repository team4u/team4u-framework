import java.io.File
import java.util.ArrayList
import java.util.List

File treeFile = new File(basedir, "target/consumer-dependency-runtime.tree")
if (!treeFile.isFile() || treeFile.length() == 0L) {
    throw new AssertionError("Runtime dependency tree is missing or empty: " + treeFile)
}

List<String> banned = new ArrayList()
treeFile.eachLine { line ->
    if (line.contains("com.team4u:team4u-proxy") ||
            line.contains("net.bytebuddy") ||
            line.contains("com.fasterxml.jackson") ||
            line.contains("org.springframework")) {
        banned.add(line.trim())
    }
}

if (!banned.isEmpty()) {
    throw new AssertionError("Config-core consumer runtime tree leaks banned dependencies:\n" + banned.join("\n"))
}
