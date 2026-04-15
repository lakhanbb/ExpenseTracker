package com.lemma.expensetracker

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

// -------------------- DATA --------------------
data class Transaction(
    val title: String,
    val amount: Double,
    val bank: String,
    val mode: String,
    val app: String,
    val holder: String,
    val dateTime: String
)

// -------------------- VIEWMODEL --------------------
class ExpenseViewModel : ViewModel() {

    private val _transactions = MutableStateFlow(
        listOf(
            Transaction("Swiggy", 250.0, "HDFC Bank", "UPI", "GPay", "Swiggy", "Today"),
            Transaction("Uber", 120.0, "HDFC Bank", "Card", "HDFC", "Uber", "Today"),
            Transaction("Amazon", 999.0, "ICICI Card", "Card", "ICICI", "Amazon", "Yesterday")
        )
    )

    val transactions: StateFlow<List<Transaction>> = _transactions

    fun addTransaction(transaction: Transaction) {
        _transactions.value = _transactions.value + transaction
    }

    fun getBalance(): Double {
        return _transactions.value.sumOf {
            it.amount
        }
    }
}

// -------------------- ACTIVITY --------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExpenseTheme {
                ExpenseScreen()
            }
        }
    }
}

// -------------------- UI --------------------
@Preview
@Composable
fun ExpenseScreen(viewModel: ExpenseViewModel = viewModel()) {
    val transactions by viewModel.transactions.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                viewModel.addTransaction(
                    Transaction(
                        title = "Coffee",
                        amount = 100.0,
                        "ICICI Card",
                        mode = "UPI",
                        app = "PhonePe",
                        holder = "Cafe Coffee Day",
                        dateTime = "15 Apr 2026, 6:00 PM"
                    )
                )
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            SummaryCard(expense = viewModel.getBalance())
            TransactionList(transactions)
        }
    }
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
fun TransactionList(transactions: List<Transaction>) {

    val grouped = transactions.groupBy { it.bank }

    LazyColumn {
        grouped.forEach { (bank, txnList) ->

            item {
                ExpandableBankSection(bank, txnList)
            }
        }
    }
}

@Composable
fun ExpandableBankSection(
    bank: String,
    transactions: List<Transaction>
) {
    var expanded by remember { mutableStateOf(true) }

    val total = transactions.sumOf { it.amount }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize() // 🔥 smooth animation
    ) {

        // 🔹 Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            // Bank Name
            Text(
                text = bank,
                style = MaterialTheme.typography.titleMedium
            )

            Row(verticalAlignment = Alignment.CenterVertically) {

                // Total Expense
                Text(
                    text = "₹ $total",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Red
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Arrow
                Text(if (expanded) "▼" else "▶")
            }
        }

        // 🔹 Expandable content
        if (expanded) {
            transactions.forEach { txn ->
                TransactionItem(txn)
            }
        }
    }
}

@Composable
fun BankHeader(bank: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = bank,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun TransactionItem(txn: Transaction) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // Top Row: Title + Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(txn.title, style = MaterialTheme.typography.titleMedium)

                Text(
                    text = "₹ ${txn.amount}",
                    color = Color.Red,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Holder
            Text(
                text = "To: ${txn.holder}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Mode + App
            Text(
                text = "${txn.mode} • ${txn.app}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Date & Time
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