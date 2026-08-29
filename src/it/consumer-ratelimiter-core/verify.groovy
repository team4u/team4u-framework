import java.io.File
import java.util.ArrayList
import java.util.List

File treeFile = new File(basedir, "target/consumer-dependency-runtime.tree")
if (!treeFile.isFile() || treeFile.length() == 0L) {
    throw new AssertionError("Runtime dependency tree is missing or empty: " + treeFile)
}

List<String> lines = treeFile.readLines()
List<String> failures = new ArrayList()

// The split core artifact must be present with its rule/coordination transitive base.
if (!lines.any { it.contains("com.team4u:team4u-ratelimiter-core:jar") }) {
    failures.add("Missing direct runtime artifact: team4u-ratelimiter-core")
}
if (!lines.any { it.contains("com.team4u:team4u-kv-space:jar") }) {
    failures.add("Missing transitive runtime artifact: team4u-kv-space (NamedKvStoreRegistry)")
}
if (!lines.any { it.contains("com.team4u:team4u-config-core:jar") }) {
    failures.add("Missing transitive runtime artifact: team4u-config-core")
}

// Proxy / Spring adapters are separate artifacts and must never leak through core.
if (lines.any { it.contains("com.team4u:team4u-ratelimiter-proxy") ||
                it.contains("com.team4u:team4u-ratelimiter-spring") }) {
    failures.add("Ratelimiter proxy/spring adapters must not leak through team4u-ratelimiter-core")
}
if (lines.any { it.contains("com.team4u:team4u-proxy") }) {
    failures.add("team4u-proxy must not leak through team4u-ratelimiter-core")
}
if (lines.any { it.contains("org.springframework") }) {
    failures.add("Spring must not leak through team4u-ratelimiter-core")
}

// Core is not a Jackson runtime owner: neither the provider nor Jackson itself.
if (lines.any { it.contains("com.team4u:team4u-serializer-jackson") ||
                it.contains("com.fasterxml.jackson") }) {
    failures.add("Jackson provider/runtime must not leak through team4u-ratelimiter-core")
}

if (!failures.isEmpty()) {
    throw new AssertionError("Ratelimiter core consumer contract failed:\n" + failures.join("\n") +
            "\nTree:\n" + lines.join("\n"))
}
