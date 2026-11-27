package com.example.banksmstracker

object UnitCreditAccountingEngine {

    /**
     * محاسبه دقیق مانده بستانکاری خالص هر واحد:
     * کل بستانکاری‌ها (واریزی‌های بانکی متصل‌شده + بستانکاری‌های دستی + مانده اولیه مثبت)
     * منهای کل بدهی‌های ثبت‌شده (بر اساس مبلغ کامل بدهی‌ها + بدهی اولیه).
     * این فرمول تضمین می‌کند با تسویه مانده بدهی، تراز دقیقاً صفر شود و بستانکاری کاذب ایجاد نگردد.
     */
    fun computeAvailableCreditPerUnit(
        units: List<BuildingUnitEntity>,
        existingCharges: List<UnitChargeEntity>,
        manualEntries: List<ManualBuildingEntryEntity>,
        buildingTransactions: List<BankTransaction>
    ): Map<Int, Long> {
        val creditMap = mutableMapOf<Int, Long>()
        for (unit in units) {
            val totalDebits = existingCharges
                .filter { it.unitId == unit.id }
                .sumOf { it.amount.coerceAtLeast(0L) } +
                if (unit.openingBalance < 0) -unit.openingBalance else 0L

            val smsCredits = buildingTransactions
                .filter { it.unitId == unit.id && it.type == TransactionType.DEPOSIT }
                .sumOf { it.amount }

            val manualCredits = manualEntries
                .filter { it.entryType == "UNIT_CREDIT" && it.unitId == unit.id }
                .sumOf { it.amount }

            val initialCredit = if (unit.openingBalance > 0) unit.openingBalance else 0L
            val totalCredits = smsCredits + manualCredits + initialCredit
            val net = totalCredits - totalDebits
            creditMap[unit.id] = if (net > 0L) net else 0L
        }
        return creditMap
    }

    /**
     * اعمال خودکار بستانکاری موجود هر واحد روی بدهی‌های جدید ماه/دوره:
     * بستانکاری موجود از بدهی جدید کسر می‌شود، مانده بدهی آن ردیف محاسبه و در عنوان ثبت می‌گردد.
     */
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

    fun splitExact(totalAmount: Long, count: Int): List<Long> {
        if (count <= 0) return emptyList()
        val base = totalAmount / count
        val remainder = totalAmount % count
        return List(count) { idx -> if (idx == 0) base + remainder else base }
    }
}
