import java.io.File
import java.util.ArrayList
import java.util.List

File treeFile = new File(basedir, "target/consumer-dependency-runtime.tree")
if (!treeFile.isFile() || treeFile.length() == 0L) {
    throw new AssertionError("Runtime dependency tree is missing or empty: " + treeFile)
}

List<String> lines = treeFile.readLines()
List<String> failures = new ArrayList()

// The split singleflight core plus the application-owned provider must be present.
if (!lines.any { it.contains("com.team4u:team4u-singleflight-core:jar") }) {
    failures.add("Missing direct runtime artifact: team4u-singleflight-core")
}
if (!lines.any { it.contains("com.team4u:team4u-serializer-jackson:jar") }) {
    failures.add("Missing direct runtime provider: team4u-serializer-jackson")
}
// Core owns the durable-schema Jackson edge: databind is expected at runtime.
if (!lines.any { it.contains("com.fasterxml.jackson.core:jackson-databind:jar") }) {
    failures.add("Missing runtime artifact: jackson-databind (singleflight-core durable-schema edge)")
}
// Coordination transitive base.
if (!lines.any { it.contains("com.team4u:team4u-kv-lock:jar") }) {
    failures.add("Missing transitive runtime artifact: team4u-kv-lock")
}

// Proxy and Spring adapters are separate artifacts and must stay absent.
if (lines.any { it.contains("com.team4u:team4u-singleflight-proxy") ||
                it.contains("com.team4u:team4u-singleflight-spring") }) {
    failures.add("Singleflight proxy/spring adapters must not leak through team4u-singleflight-core")
}
if (lines.any { it.contains("com.team4u:team4u-proxy") }) {
    failures.add("team4u-proxy must not leak through team4u-singleflight-core")
}
if (lines.any { it.contains("org.springframework") }) {
    failures.add("Spring must not leak through team4u-singleflight-core")
}

if (!failures.isEmpty()) {
    throw new AssertionError("Singleflight Jackson consumer contract failed:\n" + failures.join("\n") +
            "\nTree:\n" + lines.join("\n"))
}
