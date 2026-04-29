package com.fintech.expensetracker

fun Transaction.toEntity(): TransactionEntity {
    return TransactionEntity(
        id = id,
        title = title,
        amount = amount,
        bank = bank,
        mode = mode,
        app = app,
        holder = holder,
        dateTime = dateTime
    )
}

fun TransactionEntity.toModel(): Transaction {
    return Transaction(
        id = id,
        title = title,
        amount = amount,
        bank = bank,
        mode = mode,
        app = app,
        holder = holder,
        dateTime = dateTime
    )
}