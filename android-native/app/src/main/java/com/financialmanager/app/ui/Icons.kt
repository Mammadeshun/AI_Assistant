package com.financialmanager.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Line icons for the categories.
 *
 * Emoji were quicker and looked it: they carry another platform's illustration
 * style, they sit at different weights to each other, and half of them render
 * differently on every phone. A single set of thin outline icons is quieter and
 * reads as part of the app rather than pasted into it.
 */
object CategoryIcons {

    private val byName: Map<String, ImageVector> = mapOf(
        "Groceries" to Icons.Outlined.ShoppingCart,
        "Restaurants & Cafés" to Icons.Outlined.LocalCafe,
        "Transport" to Icons.Outlined.DirectionsBus,
        "Fuel & Parking" to Icons.Outlined.LocalGasStation,
        "Shopping" to Icons.Outlined.ShoppingBag,
        "Bills & Utilities" to Icons.Outlined.Bolt,
        "Rent & Mortgage" to Icons.Outlined.Home,
        "Subscriptions" to Icons.Outlined.Autorenew,
        "Health & Fitness" to Icons.Outlined.LocalHospital,
        "Entertainment" to Icons.Outlined.Movie,
        "Travel" to Icons.Outlined.Flight,
        "Cash & ATM" to Icons.Outlined.Payments,
        "Fees & Charges" to Icons.Outlined.CreditCard,
        "Education" to Icons.Outlined.MenuBook,
        "Gifts & Donations" to Icons.Outlined.Cake,
        "Insurance" to Icons.Outlined.Shield,
        "Savings & Investments" to Icons.Outlined.Savings,
        "Transfers" to Icons.Outlined.SwapHoriz,
        "Salary" to Icons.Outlined.Work,
        "Other Income" to Icons.Outlined.AutoAwesome,
        "Uncategorised" to Icons.Outlined.HelpOutline,
    )

    operator fun get(category: String): ImageVector =
        byName[category] ?: Icons.Outlined.AccountBalance

    /** The kinds of commitment, which have their own small set. */
    fun forCommitment(kind: String): ImageVector = when (kind) {
        "DEBT" -> Icons.Outlined.AccountBalance
        "INSTALMENT" -> Icons.Outlined.CreditCard
        "SUBSCRIPTION" -> Icons.Outlined.Autorenew
        "INCOME" -> Icons.Outlined.TrendingUp
        else -> Icons.Outlined.Bolt
    }

    /** How serious a note is, in the same line weight as everything else. */
    fun forSeverity(severity: com.financialmanager.app.plan.Severity): ImageVector =
        when (severity) {
            com.financialmanager.app.plan.Severity.ALERT -> Icons.Outlined.ErrorOutline
            com.financialmanager.app.plan.Severity.WARNING -> Icons.Outlined.WarningAmber
            com.financialmanager.app.plan.Severity.GOOD -> Icons.Outlined.CheckCircle
            com.financialmanager.app.plan.Severity.INFO -> Icons.Outlined.Lightbulb
        }
}
