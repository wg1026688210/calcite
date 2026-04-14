/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
val nettyVersion = rootProject.extra["netty.version"] as String
val mysqlConnectorVersion = rootProject.extra["mysql-connector-java.version"] as String
val shardingsphereVersion = rootProject.extra["shardingsphere.version"] as String

tasks.compileTestJava {
    dependsOn(":milvus:compileTestJava")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.remove("-Werror")
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-options"))
}

tasks.test {
    // Pass mysql.driver.version to tests for version-conditional SSL tests
    systemProperty("mysql.driver.version", rootProject.extra["mysql-connector-java.version"])
}

dependencies {
    api(project(":milvus"))
    api(project(":core"))

    implementation("io.milvus:milvus-sdk-java:2.5.13")

    implementation("io.netty:netty-common:$nettyVersion")
    implementation("io.netty:netty-transport:$nettyVersion")
    implementation("io.netty:netty-handler:$nettyVersion")
    implementation("io.netty:netty-codec:$nettyVersion")

    // ShardingSphere: only protocol layer, no proxy-frontend
    implementation("org.apache.shardingsphere:shardingsphere-protocol-mysql:$shardingsphereVersion")
    implementation("org.apache.shardingsphere:shardingsphere-database-protocol-core:$shardingsphereVersion")

    implementation("org.slf4j:slf4j-api")

    // BouncyCastle for auto SSL certificate generation (like ShardingSphere)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")

    // YAML configuration support
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":testkit"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("mysql:mysql-connector-java:$mysqlConnectorVersion")
    testImplementation(project(":milvus"))
    testImplementation(project(path = ":milvus", configuration = "testOutput"))
}

// Fat JAR configuration
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    // Collect all service files manually
    val serviceFiles = mutableMapOf<String, MutableSet<String>>()
    configurations.runtimeClasspath.get().forEach { jar ->
        if (jar.isFile && jar.name.endsWith(".jar")) {
            zipTree(jar).matching { include("META-INF/services/*") }.forEach { file ->
                val serviceName = file.name
                val implementations = file.readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .toSet()
                serviceFiles.getOrPut(serviceName) { mutableSetOf() }.addAll(implementations)
            }
        }
    }

    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/services/*")
    }
    with(tasks.jar.get())

    // Add merged service files
    serviceFiles.forEach { (name, implementations) ->
        val content = implementations.joinToString("\n")
        val serviceFile = File(temporaryDir, "META-INF/services/$name")
        serviceFile.parentFile.mkdirs()
        serviceFile.writeText(content)
    }
    from(temporaryDir)

    manifest {
        attributes["Main-Class"] = "org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
