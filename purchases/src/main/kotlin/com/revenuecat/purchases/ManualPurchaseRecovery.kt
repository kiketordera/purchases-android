@file:OptIn(InternalRevenueCatAPI::class)

package com.revenuecat.purchases

import com.revenuecat.purchases.common.BillingAbstract
import com.revenuecat.purchases.common.DateProvider
import com.revenuecat.purchases.common.sha256
import com.revenuecat.purchases.google.originalGooglePurchase
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.models.PurchaseState
import com.revenuecat.purchases.models.StoreTransaction
import java.util.concurrent.atomic.AtomicBoolean

/** A user-initiated recovery returns the exact transaction only after its receipt is acknowledged. */
internal class ManualPurchaseRecovery(
    private val billing: BillingAbstract,
    private val postReceiptHelper: PostReceiptHelper,
    private val dateProvider: DateProvider,
) {
    private val inProgress = AtomicBoolean(false)
    val isInProgress: Boolean get() = inProgress.get()

    @Suppress("LongMethod")
    fun recover(
        expectedAppUserID: String,
        productId: String,
        purchaseStartedAtMillis: Long,
        eligibleIdentity: () -> Boolean,
        callback: PurchaseCallback,
    ) {
        if (!eligibleIdentity() || !validRequest(expectedAppUserID, productId, purchaseStartedAtMillis)) {
            callback.onError(PurchasesError(PurchasesErrorCode.PurchaseNotAllowedError), false)
            return
        }
        if (!inProgress.compareAndSet(false, true)) {
            callback.onError(PurchasesError(PurchasesErrorCode.OperationAlreadyInProgressError), false)
            return
        }
        val completed = AtomicBoolean(false)
        val querySettled = AtomicBoolean(false)
        val fail: (PurchasesError) -> Unit = { error ->
            if (completed.compareAndSet(false, true)) {
                inProgress.set(false)
                callback.onError(error, false)
            }
        }
        billing.queryAllPurchases(
            expectedAppUserID,
            onReceivePurchaseHistory = history@{ transactions ->
                if (!querySettled.compareAndSet(false, true)) return@history
                val candidates = recoveryCandidates(transactions, expectedAppUserID, productId, purchaseStartedAtMillis)
                val purchased = candidates.filter { it.purchaseState == PurchaseState.PURCHASED }
                val transaction = purchased.singleOrNull()
                if (!eligibleIdentity() || transaction == null || candidates.size != 1) {
                    val code = if (candidates.singleOrNull()?.purchaseState == PurchaseState.PENDING) {
                        PurchasesErrorCode.PaymentPendingError
                    } else {
                        PurchasesErrorCode.PurchaseNotAllowedError
                    }
                    fail(PurchasesError(code))
                } else {
                    postReceiptHelper.postTransactionAndConsumeIfNeeded(
                        purchase = transaction,
                        storeProduct = null,
                        subscriptionOptionForProductIDs = null,
                        isRestore = true,
                        appUserID = expectedAppUserID,
                        initiationSource = PostReceiptInitiationSource.RESTORE,
                        sdkOriginated = false,
                        onSuccess = { acknowledged, info ->
                            if (completed.compareAndSet(false, true)) {
                                inProgress.set(false)
                                callback.onCompleted(acknowledged, info)
                            }
                        },
                        onError = { _, error -> fail(error) },
                    )
                }
            },
            onReceivePurchaseHistoryError = { error ->
                if (querySettled.compareAndSet(false, true)) fail(error)
            },
        )
    }

    private fun validRequest(owner: String, productId: String, startedAt: Long): Boolean =
        owner.isNotBlank() && productId.isNotBlank() && startedAt > 0 &&
            startedAt <= dateProvider.now.time + CLOCK_SKEW_MILLIS

    private fun recoveryCandidates(
        transactions: List<StoreTransaction>,
        owner: String,
        productId: String,
        startedAt: Long,
    ): List<StoreTransaction> = transactions.filter { transaction ->
        productId in transaction.productIds &&
            transaction.purchaseTime >= startedAt - CLOCK_SKEW_MILLIS &&
            transaction.purchaseTime <= dateProvider.now.time + CLOCK_SKEW_MILLIS &&
            transaction.originalGooglePurchase?.accountIdentifiers?.obfuscatedAccountId == owner.sha256()
    }.distinctBy { it.purchaseToken }

    private companion object {
        const val CLOCK_SKEW_MILLIS = 30_000L
    }
}
