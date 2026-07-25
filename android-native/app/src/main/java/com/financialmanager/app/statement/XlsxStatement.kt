package com.financialmanager.app.statement

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * Reads the spreadsheet Revolut calls a CSV.
 *
 * Choosing "Excel" in the statement export produces an .xlsx — a zip of XML —
 * and the file often arrives named .csv regardless. Fed to a text parser it is
 * binary noise, which is exactly how it looks when an import fails for no
 * visible reason.
 *
 * Parsed by hand rather than with a spreadsheet library: the alternative is
 * several megabytes of Apache POI in the APK to read four columns.
 */
object XlsxStatement {

    /** The local-file-header magic every zip starts with. */
    fun looksLikeXlsx(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() && header[3] == 4.toByte()

    fun parse(file: File, defaultCurrency: String = "EUR"): List<ParsedTransaction> {
        val rows = try {
            ZipFile(file).use { zip ->
                val sheet = zip.entries().asSequence()
                    .firstOrNull { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                    ?: throw StatementError("That spreadsheet has no sheets in it.")

                val strings = zip.getEntry("xl/sharedStrings.xml")
                    ?.let { zip.getInputStream(it).use(::readSharedStrings) }
                    ?: emptyList()

                zip.getInputStream(sheet).use { readSheet(it, strings) }
            }
        } catch (error: StatementError) {
            throw error
        } catch (error: Throwable) {
            throw StatementError(
                "That spreadsheet could not be read (${error.javaClass.simpleName}: ${error.message})."
            )
        }

        if (rows.isEmpty()) throw StatementError("That spreadsheet is empty.")
        return Tabular.parseRows(rows, defaultCurrency, "xlsx")
    }

    /**
     * The workbook's string pool. Cell values are indexes into this rather than
     * text, so nothing can be read without it.
     */
    private fun readSharedStrings(input: InputStream): List<String> {
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var insideItem = false
        var insideText = false

        parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, name: String?, attrs: Attributes?) {
                when (localName(name)) {
                    "si" -> { insideItem = true; current.setLength(0) }
                    // A string split across formatting runs arrives as several
                    // <t> elements inside one <si>, and has to be joined.
                    "t" -> insideText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (insideItem && insideText) current.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, name: String?) {
                when (localName(name)) {
                    "t" -> insideText = false
                    "si" -> { strings += current.toString(); insideItem = false }
                }
            }
        })
        return strings
    }

    private fun readSheet(input: InputStream, strings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var column = 0
        var type = ""
        var value = StringBuilder()
        var insideValue = false

        parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, name: String?, attrs: Attributes?) {
                when (localName(name)) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        // Empty cells are simply absent, so the column has to come
                        // from the reference ("C7") rather than from counting.
                        column = columnOf(attrs?.getValue("r"))
                        type = attrs?.getValue("t").orEmpty()
                        value = StringBuilder()
                    }
                    "v", "t" -> insideValue = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (insideValue) value.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, name: String?) {
                when (localName(name)) {
                    "v", "t" -> insideValue = false
                    "c" -> {
                        val raw = value.toString()
                        row[column] = when (type) {
                            "s" -> raw.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                            else -> raw
                        }
                    }
                    "row" -> {
                        if (row.isNotEmpty()) {
                            val width = (row.keys.maxOrNull() ?: 0) + 1
                            rows += (0 until width).map { row[it].orEmpty() }
                        }
                    }
                }
            }
        })
        return rows
    }

    private fun parse(input: InputStream, handler: DefaultHandler) {
        SAXParserFactory.newInstance().apply {
            // These files come from the user's own bank, but a parser that
            // resolves external entities is a liability whatever the source.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }.newSAXParser().parse(input, handler)
    }

    private fun localName(name: String?): String = name.orEmpty().substringAfterLast(':')

    /** "AB12" is column 27. */
    private fun columnOf(reference: String?): Int {
        if (reference.isNullOrEmpty()) return 0
        var index = 0
        for (char in reference) {
            if (!char.isLetter()) break
            index = index * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }
}
