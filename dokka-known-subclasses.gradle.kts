// Dokka configuration: aggregates module docs and adds "Known Subclasses" sections
// to parent class pages across all documented modules.

val dokkaModules = listOf(
    ":multipaz",
    ":multipaz-compose",
    ":multipaz-dcapi",
    ":multipaz-doctypes",
    ":multipaz-longfellow",
    ":multipaz-cbor-rpc",
    ":multipaz-android-legacy",
)

dependencies {
    dokkaModules.forEach { "dokka"(project(it)) }
}

subprojects {
    plugins.withId("org.jetbrains.dokka") {
        dependencies {
            "dokkaPlugin"(project(":dokka-known-subclasses"))
        }
    }
}

// Collect cross-module inheritance data for the "Known Subclasses" Dokka plugin.
val inheritorsFile = layout.buildDirectory.file("dokka/inheritors.json")

val collectInheritanceData by tasks.registering {
    description = "Scans Kotlin sources across all modules to build cross-module class hierarchy data"
    group = "documentation"

    val sourceDirs = dokkaModules.map { project.project(it).projectDir.resolve("src") }
    inputs.files(sourceDirs.filter { it.exists() })
    outputs.file(inheritorsFile)

    doLast {
        // Map of parent FQN -> set of "childFqn::childName" strings
        val inheritanceMap = mutableMapOf<String, MutableSet<String>>()

        fun extractTypeName(typeRef: String): String {
            return typeRef.substringBefore('<').substringBefore('(').trim()
        }

        fun parseSupertypes(supertypesStr: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            val current = StringBuilder()
            for (ch in supertypesStr) {
                when {
                    ch == '<' || ch == '(' -> { depth++; current.append(ch) }
                    ch == '>' || ch == ')' -> { depth--; current.append(ch) }
                    ch == ',' && depth == 0 -> {
                        val name = extractTypeName(current.toString().trim())
                        if (name.isNotEmpty()) result.add(name)
                        current.clear()
                    }
                    else -> current.append(ch)
                }
            }
            val last = extractTypeName(current.toString().trim())
            if (last.isNotEmpty()) result.add(last)
            return result
        }

        fun resolveParentFqn(lines: List<String>, simpleName: String, currentPackage: String): String {
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") && trimmed.endsWith(".$simpleName")) {
                    return trimmed.removePrefix("import ").trim()
                }
            }
            return if (currentPackage.isEmpty()) simpleName else "$currentPackage.$simpleName"
        }

        val classPattern = Regex(
            """(?:(?:abstract|open|sealed|data|enum|value|inner|private|protected|internal|public|actual|expect|annotation)\s+)*""" +
            """(class|interface|object)\s+(\w+)""" +
            """(?:\s*<[^>]*>)?""" +
            """(?:\s*(?:private|internal|public)?\s*(?:constructor\s*)?\([^)]*\))?""" +
            """\s*(?::\s*(.+?))?\s*[{$]"""
        )

        for (srcDir in sourceDirs) {
            if (!srcDir.exists()) continue
            srcDir.walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    val lines = file.readLines()
                    var packageName = ""
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("package ")) {
                            packageName = trimmed.removePrefix("package ").trim()
                            break
                        }
                    }

                    val fullText = lines.joinToString(" ")
                    for (match in classPattern.findAll(fullText)) {
                        val className = match.groupValues[2]
                        val supertypesStr = match.groupValues[3].trim()
                        if (supertypesStr.isEmpty()) continue

                        val childFqn = if (packageName.isEmpty()) className else "$packageName.$className"
                        for (parentSimpleName in parseSupertypes(supertypesStr)) {
                            if (parentSimpleName in listOf("Any", "Object", "Enum")) continue
                            if (parentSimpleName.contains(" ") || parentSimpleName.isEmpty()) continue
                            if (!parentSimpleName.first().isUpperCase()) continue
                            val parentFqn = resolveParentFqn(lines, parentSimpleName, packageName)
                            inheritanceMap.getOrPut(parentFqn) { mutableSetOf() }
                                .add("$childFqn::$className")
                        }
                    }
                }
        }

        // Compute transitive closure: if A extends B extends C, C should list both B and A.
        // Build a child->parents map from the existing data to walk up the hierarchy.
        val childToParents = mutableMapOf<String, MutableSet<String>>()
        for ((parent, children) in inheritanceMap) {
            for (entry in children) {
                val childFqn = entry.substringBefore("::")
                childToParents.getOrPut(childFqn) { mutableSetOf() }.add(parent)
            }
        }
        // For each child, propagate it up to all ancestors
        for ((parent, children) in inheritanceMap.toMap()) {
            for (entry in children.toSet()) {
                val childFqn = entry.substringBefore("::")
                val childName = entry.substringAfter("::")
                // Walk up from this parent through its own ancestors
                val visited = mutableSetOf<String>()
                val queue = ArrayDeque<String>()
                val grandparents = childToParents[parent]
                if (grandparents != null) queue.addAll(grandparents)
                while (queue.isNotEmpty()) {
                    val ancestor = queue.removeFirst()
                    if (!visited.add(ancestor)) continue
                    inheritanceMap.getOrPut(ancestor) { mutableSetOf() }
                        .add("$childFqn::$childName")
                    val further = childToParents[ancestor]
                    if (further != null) queue.addAll(further)
                }
            }
        }

        val output = inheritorsFile.get().asFile
        output.parentFile.mkdirs()
        val sb = StringBuilder()
        sb.appendLine("{")
        val entries = inheritanceMap.entries.toList()
        entries.forEachIndexed { i, (parent, children) ->
            sb.append("  \"$parent\": [")
            val childList = children.toList()
            childList.forEachIndexed { j, entry ->
                val (fqn, name) = entry.split("::", limit = 2)
                sb.append("{\"fqn\": \"$fqn\", \"name\": \"$name\"}")
                if (j < childList.size - 1) sb.append(", ")
            }
            sb.append("]")
            if (i < entries.size - 1) sb.appendLine(",") else sb.appendLine()
        }
        sb.appendLine("}")
        output.writeText(sb.toString())

        logger.lifecycle("Collected ${inheritanceMap.values.sumOf { it.size }} inheritance entries for ${inheritanceMap.size} parent classes -> $output")
    }
}

// Make all Dokka tasks depend on inheritance data collection and pass the file path
subprojects {
    tasks.matching { it.name.startsWith("dokka") }.configureEach {
        dependsOn(collectInheritanceData)
        doFirst {
            System.setProperty("dokka.knownSubclasses.dataFile", inheritorsFile.get().asFile.absolutePath)
        }
    }
}