package com.team4u.framework.lease.jdbc;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JdbcLeaseRuntimeBoundaryTest {

    @Test
    public void runtimeDependencyTreeContainsOnlyProductionLeaseEdges() throws IOException {
        Path tree = runtimeTree();
        Assert.assertTrue("missing dependency tree: " + tree
                        + " (run through Maven so the tree is generated from the current POM)",
                Files.exists(tree));
        String dependencies = new String(Files.readAllBytes(tree), StandardCharsets.UTF_8);

        assertAbsent(dependencies, "team4u-serializer-jackson");
        assertAbsent(dependencies, "com.fasterxml.jackson");
        assertAbsent(dependencies, "com.h2database:h2");
        assertAbsent(dependencies, "junit:junit");
        assertAbsent(dependencies, "org.slf4j:slf4j-simple");

        assertPresent(dependencies, "com.team4u:team4u-lease:jar");
        assertPresent(dependencies, "com.team4u:team4u-base:jar");
        assertPresent(dependencies, "com.team4u:team4u-base-jdbc:jar");
        assertPresent(dependencies, "com.team4u:team4u-serializer-json:jar");
        assertPresent(dependencies, "org.slf4j:slf4j-api:jar");
    }


    private static Path runtimeTree() {
        String configured = System.getProperty("lease.jdbc.runtime.tree");
        return configured == null
                ? Paths.get("target", "lease-jdbc-runtime.tree")
                : Paths.get(configured);
    }

    private static void assertPresent(String dependencies, String expected) {
        Assert.assertTrue("lease-jdbc runtime tree must contain " + expected
                + ":\n" + dependencies, dependencies.contains(expected));
    }

    private static void assertAbsent(String dependencies, String banned) {
        Assert.assertFalse("lease-jdbc runtime tree leaks " + banned + ":\n" + dependencies,
                dependencies.contains(banned));
    }
}
