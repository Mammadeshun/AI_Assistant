package com.financialmanager.app.statement

import android.content.Context
import android.net.Uri
import com.financialmanager.app.categorise.Categoriser
import com.financialmanager.app.data.FinanceDatabase
import com.financialmanager.app.data.TransactionRow
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportResult(
    val parsed: Int,
    val added: Int,
    val datesEstimated: Int,
    val format: String,
)

/**
 * Reads a statement the user picked from their phone and stores what it finds.
 *
 * The whole job happens on the device — the file is opened, parsed and written
 * to the local database, and nothing leaves the phone at any point.
 */
class StatementImporter(private val context: Context) {

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw StatementError("That file could not be opened.")

        if (bytes.size > MAX_BYTES) {
            throw StatementError("That file is larger than 20 MB.")
        }

        // Detected from the bytes rather than the name: files arrive from phone
        // storage with all sorts of names.
        val isPdf = bytes.size > 4 && String(bytes.copyOfRange(0, 5)) == "%PDF-"
        val parsed = if (isPdf) parsePdf(bytes) else CsvStatement.parse(bytes)

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

        // IGNORE on conflict: rows already present are left exactly as they are,
        // which is what makes importing an overlapping statement safe — and
        // keeps any category the user has since set by hand.
        val inserted = FinanceDatabase.get(context).transactions().insertAll(rows)

        ImportResult(
            parsed = rows.size,
            added = inserted.count { it != -1L },
            datesEstimated = parsed.count { it.dateEstimated },
            format = if (isPdf) "PDF" else "CSV",
        )
    }

    private fun parsePdf(bytes: ByteArray): List<ParsedTransaction> {
        // PDFBox needs this before it can load its font resources on Android.
        PDFBoxResourceLoader.init(context.applicationContext)

        val text = try {
            PDDocument.load(bytes).use { document ->
                if (document.isEncrypted) {
                    throw StatementError(
                        "That PDF is password-protected. Remove the password and try again."
                    )
                }
                // Sorting by position interleaves the columns of this statement
                // character by character once a description is long enough to
                // overlap the next column, turning a row into unreadable soup.
                // The order the text was written in is the reading order here.
                PDFTextStripper().getText(document)
            }
        } catch (error: StatementError) {
            throw error
        } catch (error: Exception) {
            throw StatementError("That file could not be read as a PDF: ${error.message}")
        }

        return PdfStatement.parseLines(text.lineSequence())
    }

    private companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}
