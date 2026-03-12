package org.multipaz.dokka

import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.DClass
import org.jetbrains.dokka.model.DInterface
import org.jetbrains.dokka.model.DObject
import org.jetbrains.dokka.model.DisplaySourceSet
import org.jetbrains.dokka.pages.*
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.transformers.pages.PageTransformer

class KnownSubclassesPageTransformer(private val context: DokkaContext) : PageTransformer {

    private data class SubclassInfo(
        val dri: DRI,
        val name: String
    )

    override fun invoke(input: RootPageNode): RootPageNode {
        // Phase 1: Walk all pages, collect supertype relationships
        val inheritorMap = mutableMapOf<DRI, MutableList<SubclassInfo>>()
        collectInheritanceInfo(input, inheritorMap)

        // Also read cross-module inheritance data if available
        readCrossModuleData(inheritorMap)

        // Compute transitive closure: if A extends B extends C, C should list both B and A.
        val childToParents = mutableMapOf<DRI, MutableSet<DRI>>()
        for ((parent, children) in inheritorMap) {
            for (child in children) {
                childToParents.getOrPut(child.dri) { mutableSetOf() }.add(parent)
            }
        }
        for ((parent, children) in inheritorMap.toMap()) {
            for (child in children.toList()) {
                val visited = mutableSetOf<DRI>()
                val queue = ArrayDeque<DRI>()
                childToParents[parent]?.let { queue.addAll(it) }
                while (queue.isNotEmpty()) {
                    val ancestor = queue.removeFirst()
                    if (!visited.add(ancestor)) continue
                    inheritorMap.getOrPut(ancestor) { mutableListOf() }.add(child)
                    childToParents[ancestor]?.let { queue.addAll(it) }
                }
            }
        }

        if (inheritorMap.isEmpty()) return input

        // Phase 2: Add "Known Subclasses" section to parent class pages
        return input.transformContentPagesTree { page ->
            if (page is ClasslikePageNode) {
                val pageDri = page.dri.first()
                val subclasses = inheritorMap[pageDri]
                if (!subclasses.isNullOrEmpty()) {
                    val sourceSets = page.content.sourceSets
                    val newContent = addKnownSubclassesSection(
                        page.content,
                        pageDri,
                        subclasses.distinctBy { it.dri }.sortedBy { it.name },
                        sourceSets
                    )
                    page.modified(content = newContent)
                } else {
                    page
                }
            } else {
                page
            }
        }
    }

    private fun collectInheritanceInfo(
        node: PageNode,
        inheritorMap: MutableMap<DRI, MutableList<SubclassInfo>>
    ) {
        if (node is ClasslikePageNode) {
            for (documentable in node.documentables) {
                val supertypes = when (documentable) {
                    is DClass -> documentable.supertypes
                    is DInterface -> documentable.supertypes
                    is DObject -> documentable.supertypes
                    else -> emptyMap()
                }
                for ((_, typeConstructors) in supertypes) {
                    for (tc in typeConstructors) {
                        val parentDri = tc.typeConstructor.dri
                        if (parentDri.packageName == "kotlin" && parentDri.classNames == "Any") continue
                        if (parentDri.packageName == "java.lang" && parentDri.classNames == "Object") continue

                        inheritorMap.getOrPut(parentDri) { mutableListOf() }.add(
                            SubclassInfo(
                                dri = documentable.dri,
                                name = documentable.name ?: "Unknown"
                            )
                        )
                    }
                }
            }
        }
        node.children.forEach { collectInheritanceInfo(it, inheritorMap) }
    }

    private fun readCrossModuleData(inheritorMap: MutableMap<DRI, MutableList<SubclassInfo>>) {
        val jsonPath = System.getProperty("dokka.knownSubclasses.dataFile") ?: return
        val file = java.io.File(jsonPath)
        if (!file.exists()) return

        try {
            val content = file.readText().trim()
            if (content.isEmpty() || content == "{}") return
            parseInheritorsJson(content, inheritorMap)
        } catch (e: Exception) {
            context.logger.warn("Failed to read cross-module inheritance data: ${e.message}")
        }
    }

    private fun parseInheritorsJson(
        json: String,
        inheritorMap: MutableMap<DRI, MutableList<SubclassInfo>>
    ) {
        val entries = json.trimStart('{').trimEnd('}').trim()
        if (entries.isEmpty()) return

        var i = 0
        while (i < entries.length) {
            val keyStart = entries.indexOf('"', i)
            if (keyStart == -1) break
            val keyEnd = entries.indexOf('"', keyStart + 1)
            if (keyEnd == -1) break
            val parentFqn = entries.substring(keyStart + 1, keyEnd)

            val arrayStart = entries.indexOf('[', keyEnd)
            if (arrayStart == -1) break
            val arrayEnd = entries.indexOf(']', arrayStart)
            if (arrayEnd == -1) break
            val arrayContent = entries.substring(arrayStart + 1, arrayEnd)

            val parentDri = fqnToDri(parentFqn)

            var j = 0
            while (j < arrayContent.length) {
                val objStart = arrayContent.indexOf('{', j)
                if (objStart == -1) break
                val objEnd = arrayContent.indexOf('}', objStart)
                if (objEnd == -1) break
                val obj = arrayContent.substring(objStart + 1, objEnd)

                val fqn = extractJsonString(obj, "fqn")
                val name = extractJsonString(obj, "name")
                if (fqn != null && name != null) {
                    inheritorMap.getOrPut(parentDri) { mutableListOf() }.add(
                        SubclassInfo(dri = fqnToDri(fqn), name = name)
                    )
                }
                j = objEnd + 1
            }
            i = arrayEnd + 1
        }
    }

    private fun extractJsonString(obj: String, key: String): String? {
        val keyPattern = "\"$key\""
        val keyIdx = obj.indexOf(keyPattern)
        if (keyIdx == -1) return null
        val colonIdx = obj.indexOf(':', keyIdx + keyPattern.length)
        if (colonIdx == -1) return null
        val valStart = obj.indexOf('"', colonIdx + 1)
        if (valStart == -1) return null
        val valEnd = obj.indexOf('"', valStart + 1)
        if (valEnd == -1) return null
        return obj.substring(valStart + 1, valEnd)
    }

    private fun fqnToDri(fqn: String): DRI {
        val lastDot = fqn.lastIndexOf('.')
        return if (lastDot == -1) {
            DRI(packageName = "", classNames = fqn)
        } else {
            DRI(packageName = fqn.substring(0, lastDot), classNames = fqn.substring(lastDot + 1))
        }
    }

    private fun addKnownSubclassesSection(
        content: ContentNode,
        pageDri: DRI,
        subclasses: List<SubclassInfo>,
        sourceSets: Set<DisplaySourceSet>
    ): ContentNode {
        if (content !is ContentGroup) return content

        val dci = DCI(setOf(pageDri), ContentKind.Comment)

        val header = ContentHeader(
            children = listOf(
                ContentText(
                    text = "Known Subclasses",
                    dci = dci,
                    sourceSets = sourceSets,
                    style = emptySet()
                )
            ),
            level = 2,
            dci = dci,
            sourceSets = sourceSets,
            style = emptySet()
        )

        val subclassLinks = subclasses.map { subclass ->
            ContentGroup(
                children = listOf(
                    ContentDRILink(
                        children = listOf(
                            ContentText(
                                text = subclass.name,
                                dci = dci,
                                sourceSets = sourceSets,
                                style = emptySet()
                            )
                        ),
                        address = subclass.dri,
                        dci = dci,
                        sourceSets = sourceSets,
                        style = emptySet()
                    )
                ),
                dci = dci,
                sourceSets = sourceSets,
                style = setOf(ContentStyle.RowTitle)
            )
        }

        val table = ContentTable(
            header = emptyList(),
            children = subclassLinks,
            dci = dci,
            sourceSets = sourceSets,
            style = setOf(ContentStyle.WithExtraAttributes)
        )

        val section = ContentGroup(
            children = listOf(header, table),
            dci = dci,
            sourceSets = sourceSets,
            style = setOf(TextStyle.Block)
        )

        return content.copy(children = content.children + section)
    }
}