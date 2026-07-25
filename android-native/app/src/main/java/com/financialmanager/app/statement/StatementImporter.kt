package com.financialmanager.app.statement

import android.content.Context
import android.net.Uri
import com.financialmanager.app.categorise.Categoriser
import com.financialmanager.app.data.FinanceDatabase
import com.financialmanager.app.data.TransactionRow
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ImportResult(
    val parsed: Int,
    val added: Int,
    val datesEstimated: Int,
    val format: String,
    val pages: Int = 0,
)

/**
 * Reads a statement the user picked from their phone and stores what it finds.
 *
 * The whole job happens on the device — the file is opened, parsed and written
 * to the local database, and nothing leaves the phone at any point.
 */
class StatementImporter(private val context: Context) {

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // Streamed to a file in the cache rather than read into a byte array.
        // A statement of any length then costs the same memory, and PDFBox can
        // page through it instead of needing the whole document at once.
        val scratch = File.createTempFile("statement", null, context.cacheDir)
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                scratch.outputStream().use { output -> input.copyTo(output) }
            } ?: throw StatementError(
                "That file could not be opened. If it came from a cloud drive, " +
                    "download it to the phone first and pick it from there."
            )

            if (copied > MAX_BYTES) {
                throw StatementError(
                    "That file is ${copied / 1_000_000} MB, over the ${MAX_BYTES / 1_000_000} MB limit."
                )
            }
            if (copied == 0L) throw StatementError("That file is empty.")

            // Detected from the bytes rather than the name. Banks hand out an
            // .xlsx named .csv often enough that trusting the extension is how
            // an import fails with binary noise and no explanation.
            val header = ByteArray(8)
            scratch.inputStream().use { it.read(header) }

            var pages = 0
            val format: String
            val parsed = when {
                String(header.copyOfRange(0, 5)) == "%PDF-" -> {
                    format = "PDF"
                    val (transactions, pageCount) = parsePdf(scratch)
                    pages = pageCount
                    transactions
                }
                XlsxStatement.looksLikeXlsx(header) -> {
                    format = "Excel"
                    XlsxStatement.parse(scratch)
                }
                else -> {
                    format = "CSV"
                    CsvStatement.parse(scratch.readBytes())
                }
            }

            val rows = parsed.map { txn ->
                val verdict = Categoriser.categorise(txn.description, txn.merchant, txn.amountMinor)
                TransactionRow(
                    externalId = txn.externalId,
                    bookedAt = txn.bookedAt,
                    amountMinor = txn.amountMinor,
                    currency = txn.currency,
                    description = txn.description,
                    merchant = txn.merchant,
                    category = verdict.category,
                    isTransfer = verdict.isTransfer,
                    dateEstimated = txn.dateEstimated,
                )
            }

            // IGNORE on conflict: rows already present are left exactly as they
            // are, which is what makes importing an overlapping statement safe —
            // and keeps any category the user has since set by hand.
            val inserted = FinanceDatabase.get(context).transactions().insertAll(rows)

            ImportResult(
                parsed = rows.size,
                added = inserted.count { it != -1L },
                datesEstimated = parsed.count { it.dateEstimated },
                format = format,
                pages = pages,
            )
        } finally {
            scratch.delete()
        }
    }

    private fun parsePdf(file: File): Pair<List<ParsedTransaction>, Int> {
        // PDFBox needs this before it can load its font resources on Android,
        // and it needs somewhere to put scratch files — the default temp
        // directory does not exist on Android.
        PDFBoxResourceLoader.init(context.applicationContext)
        System.setProperty("java.io.tmpdir", context.cacheDir.absolutePath)

        val text: String
        val pages: Int
        try {
            // Scratch-file mode rather than in-memory. Measured against a
            // 63-page statement this parses inside 64 MB either way, so this is
            // insurance against a much longer one rather than a fix for a
            // known problem.
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                if (document.isEncrypted) {
                    throw StatementError(
                        "That PDF is password-protected. Remove the password and try again."
                    )
                }
                pages = document.numberOfPages
                // Sorting by position interleaves this statement's columns
                // character by character once a description is long enough to
                // overlap the next one. The order the text was written in is
                // the reading order here.
                text = PDFTextStripper().getText(document)
            }
        } catch (error: StatementError) {
            throw error
        } catch (error: OutOfMemoryError) {
            // Deliberately caught: this is an Error rather than an Exception, so
            // it would otherwise sail past every handler and kill the import
            // with nothing shown on screen.
            throw StatementError(
                "That statement is too large for this phone's memory. Export a shorter " +
                    "date range, or use the CSV format instead."
            )
        } catch (error: Throwable) {
            throw StatementError(
                "That file could not be read as a PDF (${error.javaClass.simpleName}: " +
                    "${error.message})."
            )
        }

        if (text.isBlank()) {
            throw StatementError(
                "No text could be read from that PDF over $pages pages. If it is a scan or " +
                    "a photo rather than a downloaded statement, there is no text in it to " +
                    "read — download the statement from your bank app instead."
            )
        }

        return PdfStatement.parseLines(text.lineSequence()) to pages
    }

    private companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
    }
}
