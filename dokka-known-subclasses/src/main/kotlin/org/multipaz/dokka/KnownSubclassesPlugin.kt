package org.multipaz.dokka

import org.jetbrains.dokka.CoreExtensions
import org.jetbrains.dokka.plugability.DokkaPlugin
import org.jetbrains.dokka.plugability.DokkaPluginApiPreview
import org.jetbrains.dokka.plugability.PluginApiPreviewAcknowledgement

class KnownSubclassesPlugin : DokkaPlugin() {

    val knownSubclassesTransformer by extending {
        CoreExtensions.pageTransformer providing ::KnownSubclassesPageTransformer
    }

    @OptIn(DokkaPluginApiPreview::class)
    override fun pluginApiPreviewAcknowledgement() = PluginApiPreviewAcknowledgement
}