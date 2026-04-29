package com.fintech.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.lifecycle.ViewModelProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.mlkit.vision.text.latin.TextRecognizerOptions


// -------------------- DATA --------------------
data class Transaction(
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val bank: String,
    val mode: String,
    val app: String,
    val holder: String,
    val dateTime: String
)

// -------------------- ACTIVITY --------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.getDatabase(this)
        val transactionDao = db.transactionDao()
        val categoryDao = db.categoryDao()

        val viewModel = ExpenseViewModel(
            transactionDao,
            categoryDao
        )

        // 🔥 HANDLE SHARE INTENT
        val imageUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)

        imageUri?.let { uri ->
            runOCRPreview(this, uri, viewModel)
        }

        setContent {
            ExpenseTheme {
                ExpenseScreen(viewModel)
            }
        }
    }
}


fun runOCRPreview(
    context: Context,
    uri: Uri,
    viewModel: ExpenseViewModel
) {
    val image = InputImage.fromFilePath(context, uri)
    val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    recognizer.process(image)
        .addOnSuccessListener { result ->

            val text = result.text
            val txn = parseTransaction(text)

            // Show preview first instead of direct DB save
            viewModel.showTransactionPreview(txn)
        }

}


fun parseTransaction(text: String): Transaction {

    val lines = text.split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    println("OCR Lines -> $lines")

    // ------------------------------------------------
    // 1. AMOUNT (REFINED OCR EXTRACTION)
    // ------------------------------------------------

    var amount = 0.0

    val ignoredWords = listOf(
        "transaction", "utr", "id", "upi", "account", "bank", "from", "to:", "@",
        "google transaction", "completed", "balance", "ref", "no:", "date", "time",
        "mobile", "phone", "+91", "contact", "closing", "available", "order"
    )

    // We collect candidates with metadata to prioritize later
    val candidates = lines.mapIndexedNotNull { index, line ->
        val cleanLine = line.trim()

        // Skip obvious non-amount lines
        if (ignoredWords.any { cleanLine.contains(it, true) } ||
            cleanLine.contains("X", true) ||
            cleanLine.contains("xxxx", true) ||
            cleanLine.contains("am", true) ||
            cleanLine.contains("pm", true)
        ) return@mapIndexedNotNull null

        // Capture optional symbol and the numeric value
        // Handles: ₹500, Rs. 500, 500.00, R 500
        val match = Regex("(₹|Rs\\.?|R|Rs)?\\s*([\\d,]{1,}(?:\\.\\d{1,2})?)")
            .find(cleanLine) ?: return@mapIndexedNotNull null

        var hasSymbol = match.groupValues[1].isNotBlank()
        var valueStr = match.groupValues[2].replace(",", "")

        // PAYTM FIX: OCR often misreads ₹ as 7 when it's next to a number.
        // If "Amount" is on the previous line and we have a "Rupees" line below
        // to verify, we strip the leading 7 if it doesn't match the words.
        if (!hasSymbol && index > 0 && lines[index - 1].contains("Amount", true)) {
            if (valueStr.startsWith("7") && valueStr.length > 1) {
                val nextLine = lines.getOrNull(index + 1)
                if (nextLine != null && nextLine.contains("Rupees", true)) {
                    if (!nextLine.contains("seven", true) && !nextLine.contains("seventy", true)) {
                        valueStr = valueStr.substring(1)
                        hasSymbol = true
                    }
                }
            }
        }

        val value = valueStr.toDoubleOrNull() ?: return@mapIndexedNotNull null

        // Filter out unrealistic amounts (e.g. part of a date or ID)
        if (value !in 1.0..100000.0) return@mapIndexedNotNull null

        val hasKeyword = cleanLine.contains("paid", true) ||
                cleanLine.contains("sent", true) ||
                cleanLine.contains("amount", true) ||
                (index > 0 && lines[index - 1].contains("amount", true))

        Triple(value, hasSymbol, hasKeyword)
    }

    // Heuristic Priority:
    // 1. Largest amount with BOTH symbol and keyword (e.g., "Paid ₹500")
    // 2. Largest amount with a symbol (e.g., "₹ 500")
    // 3. Largest amount with a keyword (e.g., "Amount: 500")
    // 4. Just the largest standalone number found
    amount = candidates.filter { it.second && it.third }.map { it.first }.maxOrNull()
        ?: candidates.filter { it.second }.map { it.first }.maxOrNull()
                ?: candidates.filter { it.third }.map { it.first }.maxOrNull()
                ?: candidates.map { it.first }.maxOrNull()
                ?: 0.0

    // ------------------------------------------------
    // 2. RECEIVER NAME (GENERALIZED + PAYTM FIX)
    // ------------------------------------------------

    var paidTo = "Unknown"

    // Priority 1 → exact "Paid to"
    val paidToIndex = lines.indexOfFirst {
        it.equals("Paid to", true)
    }

    if (paidToIndex != -1) {
        for (i in paidToIndex + 1 until lines.size) {
            val line = lines[i]

            if (
                line.length > 2 &&
                !line.contains("Collect", true) &&
                !line.contains("Payment", true) &&
                !line.contains("Transaction", true) &&
                !line.contains("ID", true) &&
                !line.matches(Regex("^\\d+$"))
            ) {
                paidTo = line
                break
            }
        }
    }

    // Priority 2 → exact standalone "To" section (Paytm / GPay)
    if (paidTo == "Unknown") {
        val toIndex = lines.indexOfFirst {
            it.equals("To", true)
        }

        if (toIndex != -1 && toIndex + 1 < lines.size) {
            val nextLine = lines[toIndex + 1]

            if (
                nextLine.length > 2 &&
                !nextLine.contains("UPI", true) &&
                !nextLine.contains("ID", true) &&
                !nextLine.matches(Regex("^\\d+$"))
            ) {
                paidTo = nextLine
            }
        }
    }

    // Priority 3 → line starting with "To "
    if (paidTo == "Unknown") {
        val gpayLine = lines.firstOrNull {
            it.startsWith("To ", true) &&
                    !it.contains("To:", true)
        }

        if (gpayLine != null) {
            paidTo = gpayLine
                .replace(Regex("(?i)^To\\s+"), "")
                .trim()
        }
    }

    // Priority 4 → fallback after amount line (CRED etc.)
    if (paidTo == "Unknown") {
        val amountIndex = lines.indexOfFirst {
            it.replace(",", "")
                .replace("₹", "")
                .replace("R", "")
                .trim()
                .toDoubleOrNull() != null
        }

        if (amountIndex != -1 && amountIndex + 1 < lines.size) {
            val nextLine = lines[amountIndex + 1]

            if (
                nextLine.length > 2 &&
                !nextLine.contains("Paid via", true) &&
                !nextLine.contains("BANK", true) &&
                !nextLine.contains("amount", true) &&
                !nextLine.contains("Rupees", true)
            ) {
                paidTo = nextLine
            }
        }
    }



    // -----------------------------------
    // BANK + ACCOUNT LAST4 (GENERALIZED)
    // -----------------------------------

    var bank = "Unknown Bank"
    var accountLast4 = ""
    var finalDigits = ""


    // -----------------------------------
    // Step 1: Find trusted bank line
    // -----------------------------------

    var bankLine = lines.firstOrNull { line ->
        line.contains("bank", true) ||
                line.contains("from:", true) ||
                line.contains("account", true) ||
                line.contains("a/c", true)
    } ?: ""


    // -----------------------------------
    // Special case: PhonePe style
    // Debited from
    // XXXXX2860
    // -----------------------------------

    val debitedIndex = lines.indexOfFirst {
        it.contains("Debited from", true)
    }

    if (debitedIndex != -1 && debitedIndex + 1 < lines.size) {
        val nextLine = lines[debitedIndex + 1]

        // use next line for account digits
        if (nextLine.contains("X") || nextLine.contains(Regex("\\d{4}"))) {
            bankLine = "$bankLine $nextLine".trim()
        }
    }


    // -----------------------------------
    // Step 2: Extract account last 4 digits
    // -----------------------------------

    val digits = Regex("(\\d{4})")
        .findAll(bankLine)
        .map { it.groupValues[1] }
        .lastOrNull()

    if (!digits.isNullOrBlank()) {
        finalDigits = digits
        accountLast4 = "XXXX$digits"
    }


    // -----------------------------------
    // Step 3: Extract bank name dynamically
    // -----------------------------------

    if (bankLine.isNotBlank()) {

        bank = bankLine
            .replace(Regex("(?i)debited from"), "")
            .replace(Regex("(?i)from:"), "")
            .replace(Regex("(?i)account"), "")
            .replace(Regex("(?i)a/c"), "")
            .replace(Regex("\\d{4,}"), "")
            .replace("X", "")
            .replace("(", "")
            .replace(")", "")
            .trim()

        if (bank.isBlank()) {
            bank = "Unknown Bank"
        }
    }


    // -----------------------------------
    // Final formatted title
    // -----------------------------------

    val finalBankTitle = if (accountLast4.isNotBlank()) {
        "$bank $accountLast4"
    } else {
        bank
    }

    // ------------------------------------------------
    // 4. APP DETECTION (REFINED)
    // ------------------------------------------------

    var app = "UPI"

    // Priority 1: Check for explicit high-confidence markers
    when {
        lines.any { it.contains("Google transaction ID", true) } -> app = "GPay"
        lines.any { it.contains("PhonePe", true) && (it.contains("ID", true) || it.contains("Transaction", true)) } -> app = "PhonePe"
        lines.any { it.contains("Paytm", true) && it.contains("Order", true) } -> app = "Paytm"
        lines.any { it.contains("Paid via CRED", true) } -> app = "CRED"
    }

    if (app == "UPI") {
        // Priority 2: Look for branding at the bottom (Searching reversed)
        val brandingLine = lines.asReversed().take(5).firstOrNull { line ->
            line.equals("G Pay", true) ||
                    line.equals("PhonePe", true) ||
                    line.equals("Paytm", true) ||
                    line.contains("Amazon Pay", true) ||
                    line.contains("CRED", true)
        }

        if (brandingLine != null) {
            app = when {
                brandingLine.contains("G Pay", true) || brandingLine.contains("Google", true) -> "GPay"
                brandingLine.contains("PhonePe", true) -> "PhonePe"
                brandingLine.contains("Paytm", true) -> "Paytm"
                brandingLine.contains("CRED", true) -> "CRED"
                else -> "UPI"
            }
        }
    }

    if (app == "UPI") {
        // Priority 3: Check "From" or "Paid via" section
        val appLine = lines.firstOrNull {
            it.contains("Paid via", true) ||
                    it.contains("From:", true) ||
                    it.contains("Google Pay", true) ||
                    it.contains("PhonePe", true) ||
                    it.contains("Paytm", true)
        }

        if (appLine != null) {
            app = when {
                appLine.contains("Google", true) || appLine.contains("G Pay", true) -> "GPay"
                appLine.contains("PhonePe", true) -> "PhonePe"
                appLine.contains("Paytm", true) -> "Paytm"
                appLine.contains("CRED", true) -> "CRED"
                appLine.contains("Paid via", true) -> {
                    appLine.replace(Regex("(?i)Paid via"), "").trim().ifBlank { "UPI" }
                }
                else -> "UPI"
            }
        }
    }



    // ------------------------------------------------
    // 5. DATE
    // ------------------------------------------------

    val dateTime = lines.firstOrNull { line ->
        line.matches(
            Regex(
                ".*\\d{1,2}.*(am|pm|AM|PM).*" +
                        "|.*\\d{1,2}.*[A-Za-z]{3,}.*\\d{2,4}.*"
            )
        )
    } ?: "Unknown"


    println("FINAL -> Name: $paidTo")
    println("FINAL -> Amount: $amount")
    println("FINAL -> Bank: $finalBankTitle")
    println("FINAL -> App: $app")
    println("FINAL -> Date: $dateTime")

    return Transaction(
        title = paidTo,
        amount = amount,
        bank = finalBankTitle,
        mode = "UPI",
        app = app,
        holder = paidTo,
        dateTime = dateTime
    )
}

fun extractMonth(date: String): String {

    val cleanedDate = date
        .replace("on", "", true)
        .replace(",", " ")
        .replace("'", " ")
        .replace(Regex("(st|nd|rd|th)"), "")
        .trim()

    val regex = Regex(
        "\\d{1,2}\\s+([A-Za-z]{3,})\\s+(\\d{2,4})",
        RegexOption.IGNORE_CASE
    )

    val match = regex.find(cleanedDate)

    return if (match != null) {
        val month = match.groupValues[1]
            .lowercase()
            .replaceFirstChar { it.uppercase() }

        var year = match.groupValues[2]

        // Convert 2-digit year → 4-digit year
        if (year.length == 2) {
            year = "20$year"
        }

        "$month $year"
    } else {
        "Unknown"
    }
}

// -------------------- VIEWMODEL --------------------
class ExpenseViewModel(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao
) : ViewModel() {

    val transactions: StateFlow<List<Transaction>> =
        transactionDao.getAll()
            .map { list -> list.map { it.toModel() } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(),
                emptyList()
            )

    var pendingTransaction by mutableStateOf<Transaction?>(null)
        private set

    val allCategories: StateFlow<List<String>> =
        combine(
            transactions,
            categoryDao.getAll()
        ) { txnList, categoryList ->

            (
                    txnList.map { it.bank } +
                            categoryList.map { it.name }
                    )
                .distinct()
                .filter { it.isNotBlank() }

        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            emptyList()
        )

    fun addTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.insert(transaction.toEntity())
        }
    }

    fun confirmTransaction(transaction: Transaction) {
        viewModelScope.launch {

            // Save category permanently first
            if (transaction.bank.isNotBlank()) {
                categoryDao.insert(
                    CategoryEntity(
                        name = transaction.bank
                    )
                )
            }

            // Duplicate check
            val existing = transactionDao.findDuplicate(
                title = transaction.title,
                amount = transaction.amount,
                bank = transaction.bank,
                dateTime = transaction.dateTime
            )

            if (existing == null) {
                transactionDao.insert(
                    transaction.toEntity()
                )
                println("Inserted New Transaction")
            } else {
                println("Duplicate Transaction Skipped")
            }

            pendingTransaction = null
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            transactionDao.deleteById(transaction.id)
        }
    }

    fun createEmptyCategory(category: String) {
        viewModelScope.launch {
            categoryDao.insert(
                CategoryEntity(name = category)
            )
        }
    }

    fun showTransactionPreview(transaction: Transaction) {
        pendingTransaction = transaction
    }

    fun clearPendingTransaction() {
        pendingTransaction = null
    }

    fun getTotalExpense(list: List<Transaction>): Double {
        return list.sumOf { it.amount }
    }

    fun deleteCategory(category: String) {
        viewModelScope.launch {

            // delete all transactions under category
            transactionDao.deleteByCategory(category)

            // delete category itself
            categoryDao.deleteCategory(category)
        }
    }

    var editingTransaction by mutableStateOf<Transaction?>(null)
        private set

    var editingCategory by mutableStateOf<String?>(null)
        private set

    fun openTransactionEditor(transaction: Transaction) {
        editingTransaction = transaction
    }

    fun closeTransactionEditor() {
        editingTransaction = null
    }

    fun openCategoryEditor(category: String) {
        editingCategory = category
    }

    fun closeCategoryEditor() {
        editingCategory = null
    }

    fun updateCategory(oldName: String, newName: String) {
        viewModelScope.launch {
            if (newName.isBlank()) return@launch

            // update all transactions to new category
            transactionDao.updateCategoryName(
                oldName = oldName,
                newName = newName
            )

            // remove old category
            categoryDao.deleteCategory(oldName)

            // insert new category
            categoryDao.insert(
                CategoryEntity(name = newName)
            )

            closeCategoryEditor()
        }
    }

    fun updateTransaction(updated: Transaction) {
        viewModelScope.launch {
            transactionDao.insert(updated.toEntity())
            closeTransactionEditor()
        }
    }

}



// -------------------- UI --------------------
@Composable
fun ExpenseScreen(viewModel: ExpenseViewModel) {

    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.allCategories.collectAsState()

    val previewTxn = viewModel.pendingTransaction

    var selectedMonth by remember {
        mutableStateOf("All")
    }

    // Available months for dropdown
    val availableMonths = listOf("All") +
            transactions.map {
                extractMonth(it.dateTime)
            }
                .distinct()
                .filter {
                    it != "Unknown"
                }

    // Filter transactions month-wise
    val filteredTransactions =
        if (selectedMonth == "All") {
            transactions
        } else {
            transactions.filter {
                extractMonth(it.dateTime) == selectedMonth
            }
        }

    // Total should use filtered transactions
    val total = viewModel.getTotalExpense(filteredTransactions)

    previewTxn?.let {
        TransactionPreviewDialog(
            transaction = it,
            existingBanks = categories,
            onDismiss = {
                viewModel.clearPendingTransaction()
            },
            onSave = { updatedTxn ->
                viewModel.confirmTransaction(updatedTxn)
            }
        )
    }

    var showCreateCategoryDialog by remember {
        mutableStateOf(false)
    }

    if (showCreateCategoryDialog) {
        CreateCategoryDialog(
            onDismiss = {
                showCreateCategoryDialog = false
            },
            onSave = { newCategory ->
                viewModel.createEmptyCategory(newCategory)
                showCreateCategoryDialog = false
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    showCreateCategoryDialog = true
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create Category"
                )
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            // Total expense first
            SummaryCard(expense = total)

            // Month filter below total expense
            MonthFilterDropdown(
                selectedMonth = selectedMonth,
                months = availableMonths,
                onMonthSelected = {
                    selectedMonth = it
                }
            )

            // Show filtered transactions only
            TransactionList(
                transactions = filteredTransactions,
                categories = categories,
                viewModel = viewModel
            )
        }
    }

    viewModel.editingTransaction?.let { txn ->
        TransactionPreviewDialog(
            transaction = txn,
            existingBanks = categories,
            onDismiss = {
                viewModel.closeTransactionEditor()
            },
            onSave = { updatedTxn ->
                viewModel.updateTransaction(updatedTxn)
            }
        )
    }

    viewModel.editingCategory?.let { category ->
        EditCategoryDialog(
            oldCategory = category,
            onDismiss = {
                viewModel.closeCategoryEditor()
            },
            onSave = { newCategory ->
                viewModel.updateCategory(category, newCategory)
            }
        )
    }
}



@Composable
fun CreateCategoryDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var categoryName by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,

        confirmButton = {
            Button(
                onClick = {
                    if (categoryName.isNotBlank()) {
                        onSave(categoryName.trim())
                    }
                }
            ) {
                Text("Create")
            }
        },

        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },

        title = {
            Text("Create New Category")
        },

        text = {
            OutlinedTextField(
                value = categoryName,
                onValueChange = {
                    categoryName = it
                },
                label = {
                    Text("Category Name")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
fun SummaryCard(expense: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Total Expense")
            Text("₹ $expense", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
fun TransactionList(
    transactions: List<Transaction>,
    categories: List<String>,
    viewModel: ExpenseViewModel
) {
    LazyColumn {
        categories.forEach { bank ->

            val txnList = transactions.filter {
                it.bank == bank
            }

            item {
                ExpandableBankSection(
                    bank = bank,
                    transactions = txnList,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun ExpandableBankSection(
    bank: String,
    transactions: List<Transaction>,
    viewModel: ExpenseViewModel
) {
    var expanded by remember {
        mutableStateOf(true)
    }

    var showMenu by remember {
        mutableStateOf(false)
    }

    var showDeleteCategoryDialog by remember {
        mutableStateOf(false)
    }

    val total = transactions.sumOf { it.amount }

    // 🔥 Delete category confirmation
    if (showDeleteCategoryDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteCategoryDialog = false
            },

            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCategory(bank)
                        showDeleteCategoryDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },

            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteCategoryDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            },

            title = {
                Text("Delete Category")
            },

            text = {
                Text(
                    "Delete this category and all its transactions?"
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {

        // 🔥 Category Header with 3-dot menu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // Category name always visible
            Text(
                text = bank,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "₹ $total",
                style = MaterialTheme.typography.titleMedium,
                color = Color.Red
            )

            Box {

                IconButton(
                    modifier = Modifier.size(34.dp),
                    onClick = {
                        showMenu = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.Gray
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = {
                        showMenu = false
                    }
                ) {

                    DropdownMenuItem(
                        text = {
                            Text("Edit")
                        },
                        onClick = {
                            showMenu = false
                            viewModel.openCategoryEditor(bank)
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text("Delete")
                        },
                        onClick = {
                            showMenu = false
                            showDeleteCategoryDialog = true
                        }
                    )
                }
            }

            Text(
                text = if (expanded) "▼" else "▶",
                style = MaterialTheme.typography.titleSmall
            )
        }

        // 🔥 Expanded Transactions
        if (expanded) {
            transactions.forEach { txn ->
                TransactionItem(
                    txn = txn,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun TransactionItem(
    txn: Transaction,
    viewModel: ExpenseViewModel
) {

    var showMenu by remember {
        mutableStateOf(false)
    }

    var showDeleteDialog by remember {
        mutableStateOf(false)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {

            // 🔥 Top Row with 3-dot menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                // Title stays visible + dots always visible
                Text(
                    text = txn.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "₹ ${txn.amount}",
                    color = Color.Red,
                    style = MaterialTheme.typography.titleMedium
                )

                Box {

                    IconButton(
                        modifier = Modifier.size(34.dp),
                        onClick = {
                            showMenu = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = Color.Gray
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = {
                            showMenu = false
                        }
                    ) {

                        DropdownMenuItem(
                            text = {
                                Text("Edit")
                            },
                            onClick = {
                                showMenu = false
                                viewModel.openTransactionEditor(txn)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text("Delete")
                            },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }

            // 🔥 Delete confirmation popup
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showDeleteDialog = false
                    },

                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteTransaction(txn)
                                showDeleteDialog = false
                            }
                        ) {
                            Text("Delete")
                        }
                    },

                    dismissButton = {
                        TextButton(
                            onClick = {
                                showDeleteDialog = false
                            }
                        ) {
                            Text("Cancel")
                        }
                    },

                    title = {
                        Text("Confirm Delete")
                    },

                    text = {
                        Text(
                            "Are you sure you want to delete this transaction?"
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "To: ${txn.holder}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${txn.mode} • ${txn.app}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = txn.dateTime,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

// -------------------- THEME --------------------
@Composable
fun ExpenseTheme(content: @Composable () -> Unit) {
    val darkTheme = true

    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF4CAF50),
            background = Color.Black,
            surface = Color(0xFF121212)
        )
    } else {
        lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionPreviewDialog(
    transaction: Transaction,
    existingBanks: List<String>,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit
) {
    var title by remember {
        mutableStateOf(transaction.title)
    }

    var amount by remember {
        mutableStateOf(transaction.amount.toString())
    }

    var bankName by remember {
        mutableStateOf(transaction.bank)
    }

    var mode by remember {
        mutableStateOf(transaction.mode)
    }

    var app by remember {
        mutableStateOf(transaction.app)
    }

    var dateTime by remember {
        mutableStateOf(transaction.dateTime)
    }

    var expanded by remember {
        mutableStateOf(false)
    }

    AlertDialog(
        onDismissRequest = onDismiss,

        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        transaction.copy(
                            title = title,
                            holder = title,
                            amount = amount.toDoubleOrNull() ?: 0.0,
                            bank = bankName,
                            mode = mode,
                            app = app,
                            dateTime = dateTime
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },

        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },

        title = {
            Column {
                Text(
                    text = "Review Transaction Details",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Please verify and edit any details if needed before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },

        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                // Receiver Name
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                    },
                    label = {
                        Text("Receiver Name")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Amount
                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                    },
                    label = {
                        Text("Amount")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Editable OCR detected bank/category
                OutlinedTextField(
                    value = bankName,
                    onValueChange = {
                        bankName = it
                    },
                    label = {
                        Text("Bank Name / Category")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Select Existing Category")

                Spacer(modifier = Modifier.height(6.dp))

                // Dropdown for existing categories
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = {
                        expanded = !expanded
                    }
                ) {
                    OutlinedTextField(
                        value = bankName,
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text("Existing Categories")
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = expanded
                            )
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                        }
                    ) {
                        existingBanks.forEach { bank ->
                            DropdownMenuItem(
                                text = {
                                    Text(bank)
                                },
                                onClick = {
                                    bankName = bank
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Payment Mode
                OutlinedTextField(
                    value = mode,
                    onValueChange = {
                        mode = it
                    },
                    label = {
                        Text("Payment Mode")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // App
                OutlinedTextField(
                    value = app,
                    onValueChange = {
                        app = it
                    },
                    label = {
                        Text("App")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Date & Time
                OutlinedTextField(
                    value = dateTime,
                    onValueChange = {
                        dateTime = it
                    },
                    label = {
                        Text("Date & Time")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

@Composable
fun EditCategoryDialog(
    oldCategory: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var newCategory by remember {
        mutableStateOf(oldCategory)
    }

    AlertDialog(
        onDismissRequest = onDismiss,

        confirmButton = {
            Button(
                onClick = {
                    if (newCategory.isNotBlank()) {
                        onSave(newCategory.trim())
                    }
                }
            ) {
                Text("Save")
            }
        },

        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },

        title = {
            Text("Edit Category")
        },

        text = {
            OutlinedTextField(
                value = newCategory,
                onValueChange = {
                    newCategory = it
                },
                label = {
                    Text("Category Name")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthFilterDropdown(
    selectedMonth: String,
    months: List<String>,
    onMonthSelected: (String) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        }
    ) {
        OutlinedTextField(
            value = selectedMonth,
            onValueChange = {},
            readOnly = true,
            label = {
                Text("Filter by Month")
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            months.forEach { month ->
                DropdownMenuItem(
                    text = {
                        Text(month)
                    },
                    onClick = {
                        onMonthSelected(month)
                        expanded = false
                    }
                )
            }
        }
    }
}