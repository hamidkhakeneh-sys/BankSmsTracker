package com.example.banksmstracker

object UnitCreditAccountingEngine {

    fun computeAvailableCreditPerUnit(
        units: List<BuildingUnitEntity>,
        existingCharges: List<UnitChargeEntity>,
        manualEntries: List<ManualBuildingEntryEntity>,
        buildingTransactions: List<BankTransaction>
    ): Map<Int, Long> {
        val creditMap = mutableMapOf<Int, Long>()
        for (unit in units) {
            val prevDebits = existingCharges
                .filter { it.unitId == unit.id }
                .sumOf { ch -> (ch.remainingBalance ?: (ch.amount - ch.creditApplied)).coerceAtLeast(0L) } +
                if (unit.openingBalance < 0) -unit.openingBalance else 0L

            val smsCredits = buildingTransactions
                .filter { it.unitId == unit.id && it.type == TransactionType.DEPOSIT }
                .sumOf { it.amount }

            val manualCredits = manualEntries
                .filter { it.entryType == "UNIT_CREDIT" && it.unitId == unit.id }
                .sumOf { it.amount }

            val initialCredit = if (unit.openingBalance > 0) unit.openingBalance else 0L
            val totalCredits = smsCredits + manualCredits + initialCredit
            val net = totalCredits - prevDebits
            creditMap[unit.id] = if (net > 0L) net else 0L
        }
        return creditMap
    }

    fun applyExistingCreditToNewCharges(
        draftCharges: List<UnitChargeEntity>,
        units: List<BuildingUnitEntity>,
        existingCharges: List<UnitChargeEntity>,
        manualEntries: List<ManualBuildingEntryEntity>,
        buildingTransactions: List<BankTransaction>
    ): List<UnitChargeEntity> {
        val runningCreditByUnit = computeAvailableCreditPerUnit(
            units = units,
            existingCharges = existingCharges,
            manualEntries = manualEntries,
            buildingTransactions = buildingTransactions
        ).toMutableMap()

        return draftCharges.map { draft ->
            val availableCredit = runningCreditByUnit[draft.unitId] ?: 0L
            val grossAmount = draft.amount.coerceAtLeast(0L)

            if (availableCredit > 0L && grossAmount > 0L) {
                val deducted = minOf(availableCredit, grossAmount)
                val remDebt = (grossAmount - deducted).coerceAtLeast(0L)
                runningCreditByUnit[draft.unitId] = (availableCredit - deducted).coerceAtLeast(0L)

                val statusSuffix = if (remDebt == 0L) {
                    " [تسویه کامل از بستانکاری • کسر: ${formatCurrency(deducted)} ریال]"
                } else {
                    " [کسر بستانکاری: ${formatCurrency(deducted)} • مانده بدهی: ${formatCurrency(remDebt)} ریال]"
                }

                val enrichedTitle = if (draft.title.contains("کسر بستانکاری") || draft.title.contains("تسویه کامل")) {
                    draft.title
                } else {
                    "${draft.title}$statusSuffix"
                }

                draft.copy(
                    amount = grossAmount,
                    creditApplied = deducted,
                    remainingBalance = remDebt,
                    title = enrichedTitle
                )
            } else {
                draft.copy(
                    amount = grossAmount,
                    creditApplied = 0L,
                    remainingBalance = grossAmount
                )
            }
        }
    }
}
