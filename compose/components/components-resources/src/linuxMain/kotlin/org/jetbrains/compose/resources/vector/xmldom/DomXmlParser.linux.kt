package org.jetbrains.compose.resources.vector.xmldom

internal fun parse(xml: String): Element = SimpleXmlParser(xml).parse()

private class SimpleXmlParser(private val source: String) {
    private var index = 0

    fun parse(): Element {
        skipMisc()
        val root = parseElement(parentNamespaces = emptyMap())
        skipMisc()
        if (index != source.length) malformed("Unexpected content after root element")
        return root
    }

    private fun parseElement(parentNamespaces: Map<String, String>): SimpleElement {
        expect('<')
        if (peek('/') || peek('!') || peek('?')) malformed("Expected an element")
        val qualifiedName = readName()
        val rawAttributes = linkedMapOf<String, String>()
        while (true) {
            skipWhitespace()
            when {
                consume("/>") -> {
                    val namespaces = namespaceMap(parentNamespaces, rawAttributes)
                    return SimpleElement(qualifiedName, rawAttributes, namespaces, emptyList())
                }
                consume(">") -> break
                else -> {
                    val name = readName()
                    skipWhitespace()
                    expect('=')
                    skipWhitespace()
                    rawAttributes[name] = readQuotedValue()
                }
            }
        }

        val namespaces = namespaceMap(parentNamespaces, rawAttributes)
        val children = mutableListOf<Node>()
        while (true) {
            if (consume("</")) {
                val closingName = readName()
                if (closingName != qualifiedName)
                    malformed("Expected </$qualifiedName>, found </$closingName>")
                skipWhitespace()
                expect('>')
                break
            }
            when {
                consume("<!--") -> skipUntil("-->")
                consume("<![CDATA[") -> children += SimpleText(readUntil("]]>"))
                consume("<?") -> skipUntil("?>")
                peek('<') -> children += parseElement(namespaces)
                index >= source.length -> malformed("Unclosed <$qualifiedName>")
                else -> {
                    val end = source.indexOf('<', index).takeIf { it >= 0 } ?: source.length
                    val text = source.substring(index, end)
                    index = end
                    if (text.isNotEmpty()) children += SimpleText(decodeEntities(text))
                }
            }
        }
        return SimpleElement(qualifiedName, rawAttributes, namespaces, children)
    }

    private fun namespaceMap(
        inherited: Map<String, String>,
        attributes: Map<String, String>,
    ): Map<String, String> = buildMap {
        putAll(inherited)
        attributes.forEach { (name, value) ->
            when {
                name == "xmlns" -> put("", value)
                name.startsWith("xmlns:") -> put(name.substringAfter(':'), value)
            }
        }
    }

    private fun skipMisc() {
        while (true) {
            skipWhitespace()
            when {
                consume("<?") -> skipUntil("?>")
                consume("<!--") -> skipUntil("-->")
                consume("<!DOCTYPE") -> skipDeclaration()
                else -> return
            }
        }
    }

    private fun skipDeclaration() {
        var bracketDepth = 0
        var quote: Char? = null
        while (index < source.length) {
            val c = source[index++]
            if (quote != null) {
                if (c == quote) quote = null
            } else
                when (c) {
                    '\'',
                    '"' -> quote = c
                    '[' -> bracketDepth++
                    ']' -> bracketDepth--
                    '>' -> if (bracketDepth <= 0) return
                }
        }
        malformed("Unclosed declaration")
    }

    private fun readName(): String {
        val start = index
        while (index < source.length) {
            val c = source[index]
            if (!(c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.')) break
            index++
        }
        if (index == start) malformed("Expected a name")
        return source.substring(start, index)
    }

    private fun readQuotedValue(): String {
        if (index >= source.length || source[index] !in charArrayOf('\'', '"'))
            malformed("Expected quoted value")
        val quote = source[index++]
        val start = index
        while (index < source.length && source[index] != quote) index++
        if (index >= source.length) malformed("Unclosed attribute value")
        val value = source.substring(start, index)
        index++
        return decodeEntities(value)
    }

    private fun decodeEntities(value: String): String =
        ENTITY.replace(value) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val codePoint =
                        when {
                            entity.startsWith("#x", ignoreCase = true) ->
                                entity.drop(2).toIntOrNull(16)
                            entity.startsWith('#') -> entity.drop(1).toIntOrNull()
                            else -> null
                        } ?: malformed("Unsupported entity &$entity;")
                    codePoint.toChar().toString()
                }
            }
        }

    private fun readUntil(token: String): String {
        val end = source.indexOf(token, index)
        if (end < 0) malformed("Expected $token")
        val result = source.substring(index, end)
        index = end + token.length
        return result
    }

    private fun skipUntil(token: String) {
        readUntil(token)
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun expect(c: Char) {
        if (index >= source.length || source[index] != c) malformed("Expected '$c'")
        index++
    }

    private fun consume(token: String): Boolean {
        if (!source.startsWith(token, index)) return false
        index += token.length
        return true
    }

    private fun peek(c: Char): Boolean = index < source.length && source[index] == c

    private fun malformed(message: String): Nothing =
        throw MalformedXMLException("$message at character $index")

    private companion object {
        val ENTITY = Regex("&([^;]+);")
    }
}

private class SimpleNodeList(private val nodes: List<Node>) : NodeList {
    override fun item(i: Int): Node = nodes[i]

    override val length: Int
        get() = nodes.size
}

private class SimpleText(private val value: String) : Node {
    override val textContent: String = value
    override val nodeName: String = "#text"
    override val localName: String = nodeName
    override val childNodes: NodeList = SimpleNodeList(emptyList())
    override val namespaceURI: String = ""

    override fun lookupPrefix(namespaceURI: String): String = ""
}

private class SimpleElement(
    private val qualifiedName: String,
    private val attributes: Map<String, String>,
    private val namespaces: Map<String, String>,
    children: List<Node>,
) : Element {
    override val nodeName: String
        get() = qualifiedName

    override val localName: String
        get() = qualifiedName.substringAfter(':', qualifiedName)

    override val namespaceURI: String
        get() = namespaces[qualifiedName.substringBefore(':', "")].orEmpty()

    override val childNodes: NodeList = SimpleNodeList(children)
    override val textContent: String? =
        children.mapNotNull(Node::textContent).joinToString("").ifEmpty { null }

    override fun lookupPrefix(namespaceURI: String): String =
        namespaces.entries.firstOrNull { it.value == namespaceURI }?.key.orEmpty()

    override fun getAttribute(name: String): String = attributes[name].orEmpty()

    override fun getAttributeNS(nameSpaceURI: String, localName: String): String {
        return attributes.entries
            .firstOrNull { (qualifiedName, _) ->
                if (qualifiedName == "xmlns" || qualifiedName.startsWith("xmlns:"))
                    return@firstOrNull false
                val prefix = qualifiedName.substringBefore(':', "")
                val attributeLocalName = qualifiedName.substringAfter(':', qualifiedName)
                attributeLocalName == localName && namespaces[prefix].orEmpty() == nameSpaceURI
            }
            ?.value
            .orEmpty()
    }
}
