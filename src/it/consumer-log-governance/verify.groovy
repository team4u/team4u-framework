import java.io.File
import java.util.ArrayList
import java.util.List

File treeFile = new File(basedir, "target/consumer-dependency-runtime.tree")
if (!treeFile.isFile() || treeFile.length() == 0L) {
    throw new AssertionError("Runtime dependency tree is missing or empty: " + treeFile)
}

List<String> lines = treeFile.readLines()
List<String> failures = new ArrayList()

if (!lines.any { it.contains("com.team4u:team4u-log-governance:jar") }) {
    failures.add("Missing direct runtime artifact: team4u-log-governance")
}
if (!lines.any { it.contains("com.team4u:team4u-log-core:jar") }) {
    failures.add("Missing transitive runtime artifact: team4u-log-core")
}
if (!lines.any { it.contains("com.team4u:team4u-serializer-jackson:jar") }) {
    failures.add("Missing transitive runtime provider: team4u-serializer-jackson")
}
if (!lines.any { it.contains("com.fasterxml.jackson.core:jackson-databind:jar") }) {
    failures.add("Missing transitive runtime artifact: jackson-databind")
}

if (!failures.isEmpty()) {
    throw new AssertionError("Log governance consumer contract failed:\n" + failures.join("\n") +
            "\nTree:\n" + lines.join("\n"))
}
