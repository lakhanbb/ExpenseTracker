package com.fintech.expensetracker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val bank: String,
    val mode: String,
    val app: String,
    val holder: String,
    val dateTime: String
)