import java.io.File
import java.util.ArrayList
import java.util.List

File treeFile = new File(basedir, "target/consumer-dependency-runtime.tree")
if (!treeFile.isFile() || treeFile.length() == 0L) {
    throw new AssertionError("Runtime dependency tree is missing or empty: " + treeFile)
}

List<String> byteBuddy = new ArrayList()
treeFile.eachLine { line ->
    if (line.contains("net.bytebuddy")) {
        byteBuddy.add(line.trim())
    }
}

if (!byteBuddy.isEmpty()) {
    throw new AssertionError("Interface proxy consumer runtime tree contains ByteBuddy:\n" + byteBuddy.join("\n"))
}
