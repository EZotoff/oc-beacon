package dev.leonardo.ocbeacon.ui.screens.portable

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableResourcesTest {
    @Test fun `portable chrome has all translations with matching placeholders`() {
        val root = File("src/main/res")
        val source = strings(File(root, "values/portable.xml"))
        val locales = listOf("ar", "de", "es", "fr", "id", "it", "ja", "ko", "pl", "pt-rBR", "ru", "tr", "uk", "zh-rCN")
        val placeholder = Regex("%[0-9]+\\$[ds]")
        assertEquals(23, source.size)
        locales.forEach { locale ->
            val translated = strings(File(root, "values-$locale/portable.xml"))
            assertEquals(locale, source.keys, translated.keys)
            source.forEach { (key, text) ->
                assertTrue("$locale/$key", translated.getValue(key).isNotBlank())
                assertEquals("$locale/$key", placeholder.findAll(text).map { it.value }.toList(),
                    placeholder.findAll(translated.getValue(key)).map { it.value }.toList())
            }
        }
    }

    private fun strings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
}
