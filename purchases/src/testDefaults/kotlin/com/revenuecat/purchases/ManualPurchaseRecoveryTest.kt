package com.revenuecat.purchases

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.billingclient.api.Purchase
import com.revenuecat.purchases.common.sha256
import com.revenuecat.purchases.google.toStoreTransaction
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.models.StoreTransaction
import com.revenuecat.purchases.utils.stubGooglePurchase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.Date

@OptIn(ExperimentalPreviewRevenueCatPurchasesAPI::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
internal class ManualPurchaseRecoveryTest : BasePurchasesTest() {
    private val productId = "interrupted_subscription"
    private val startedAt = Date(System.currentTimeMillis() - 1_000L)
    private val callback = mockk<PurchaseCallback>(relaxed = true)

    @Before
    fun configureRecovery() {
        buildPurchases(anonymous = false, autoSync = false)
    }

    @Test
    fun `recovery posts exact queried receipt and returns that transaction after acknowledgment`() {
        val purchase = transaction()
        returnPurchases(listOf(purchase))

        recover()

        verify(exactly = 1) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                purchase, null, null, true, appUserId, PostReceiptInitiationSource.RESTORE,
                false, any(), any(),
            )
            callback.onCompleted(purchase, mockInfo)
        }
        verify(exactly = 0) { callback.onError(any(), any()) }
    }

    @Test
    fun `query result alone cannot acknowledge recovery and duplicate receipt callback is ignored`() {
        val purchase = transaction()
        returnPurchases(listOf(purchase))
        lateinit var acknowledge: SuccessfulPurchaseCallback
        every {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), captureLambda(), any(),
            )
        } answers { acknowledge = lambda<SuccessfulPurchaseCallback>().captured }

        recover()
        verify(exactly = 0) { callback.onCompleted(any(), any()) }
        acknowledge(purchase, mockInfo)
        acknowledge(purchase, mockInfo)

        verify(exactly = 1) { callback.onCompleted(purchase, mockInfo) }
    }

    @Test
    fun `duplicate query success cannot start a second receipt post`() {
        val purchase = transaction()
        lateinit var querySuccess: (List<StoreTransaction>) -> Unit
        every { mockBillingAbstract.queryAllPurchases(appUserId, any(), any()) } answers {
            querySuccess = secondArg()
        }
        every {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } answers { Unit }

        recover()
        querySuccess(listOf(purchase))
        querySuccess(listOf(purchase))

        verify(exactly = 1) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                purchase, any(), any(), any(), appUserId, any(), any(), any(), any(),
            )
        }
        verify(exactly = 0) { callback.onCompleted(any(), any()) }
    }

    @Test
    fun `obsolete query success after its failure cannot post during a newer recovery`() {
        val successes = mutableListOf<(List<StoreTransaction>) -> Unit>()
        val failures = mutableListOf<(PurchasesError) -> Unit>()
        every { mockBillingAbstract.queryAllPurchases(appUserId, any(), any()) } answers {
            successes.add(secondArg())
            failures.add(thirdArg())
        }
        recover()
        failures.single()(PurchasesError(PurchasesErrorCode.NetworkError))
        recover()

        successes.first()(listOf(transaction()))
        verify(exactly = 0) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
        val current = transaction(token = "current_recovery")
        successes.last()(listOf(current))

        verify(exactly = 1) { callback.onCompleted(current, mockInfo) }
    }

    @Test
    fun `recovery in flight rejects a new checkout without disturbing recovery`() {
        every { mockBillingAbstract.queryAllPurchases(appUserId, any(), any()) } answers { Unit }
        recover()
        val product = mockStoreProduct(listOf(productId), listOf(productId), ProductType.SUBS).first()
        var rejected = false

        purchases.purchaseWith(getPurchaseParams(product), onError = { error, _ ->
            rejected = error.code == PurchasesErrorCode.OperationAlreadyInProgressError
        }, onSuccess = { _, _ -> error("Recovery must exclude checkout") })

        assertTrue(rejected)
        verify(exactly = 0) { mockBillingAbstract.makePurchaseAsync(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { callback.onError(any(), any()) }
    }

    @Test
    fun `recovery in flight rejects a product change without disturbing recovery`() {
        every { mockBillingAbstract.queryAllPurchases(appUserId, any(), any()) } answers { Unit }
        recover()
        val product = mockStoreProduct(listOf(productId), listOf(productId), ProductType.SUBS).first()
        var rejected = false

        purchases.purchaseWith(getPurchaseParams(product, oldProductId = "old_product"), onError = { error, _ ->
            rejected = error.code == PurchasesErrorCode.OperationAlreadyInProgressError
        }, onSuccess = { _, _ -> error("Recovery must exclude product change") })

        assertTrue(rejected)
        verify(exactly = 0) { mockBillingAbstract.makePurchaseAsync(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { callback.onError(any(), any()) }
    }

    @Test
    fun `empty store query retains unresolved intent through an error`() {
        returnPurchases(emptyList())
        recover()
        verifyRejected()
    }

    @Test
    fun `matching SKU with foreign account hash is rejected before receipt posting`() {
        returnPurchases(listOf(transaction(owner = "another_account")))
        recover()
        verifyRejected()
    }

    @Test
    fun `historical purchase is rejected before receipt posting`() {
        returnPurchases(listOf(transaction(purchaseTime = 1L)))
        recover()
        verifyRejected()
    }

    @Test
    fun `future transaction is rejected before receipt posting`() {
        returnPurchases(listOf(transaction(purchaseTime = System.currentTimeMillis() + 60_000L)))
        recover()
        verifyRejected()
    }

    @Test
    fun `two matching purchased tokens are ambiguous and neither is posted`() {
        returnPurchases(listOf(transaction(token = "one"), transaction(token = "two")))
        recover()
        verifyRejected()
    }

    @Test
    fun `pending Play payment cannot acknowledge a purchased receipt`() {
        returnPurchases(listOf(transaction(state = Purchase.PurchaseState.PENDING)))
        recover()
        verifyRejected(PurchasesErrorCode.PaymentPendingError)
    }

    @Test
    fun `SDK identity changes during query cannot post the receipt`() {
        val purchase = transaction()
        every { mockBillingAbstract.queryAllPurchases(appUserId, captureLambda(), any()) } answers {
            every { mockIdentityManager.currentAppUserID } returns "changed_during_query"
            lambda<(List<StoreTransaction>) -> Unit>().captured(listOf(purchase))
        }
        recover()
        verifyRejected()
    }

    @Test
    fun `foreign SDK identity is rejected before querying Google Play`() {
        every { mockIdentityManager.currentAppUserID } returns "wrong_sdk_owner"
        recover()
        verifyRejected()
        verify(exactly = 0) { mockBillingAbstract.queryAllPurchases(any(), any(), any()) }
    }

    @Test
    fun `anonymous SDK identity is rejected before querying Google Play`() {
        every { mockIdentityManager.currentUserIsAnonymous() } returns true
        recover()
        verifyRejected()
        verify(exactly = 0) { mockBillingAbstract.queryAllPurchases(any(), any(), any()) }
    }

    @Test
    fun `automatic mode is unaffected and does not allow manual recovery`() {
        buildPurchases(anonymous = false)
        recover()
        verifyRejected()
        verify(exactly = 0) { mockBillingAbstract.queryAllPurchases(any(), any(), any()) }
    }

    @Test
    fun `receipt network failure returns no transaction and allows another explicit recovery attempt`() {
        returnPurchases(listOf(transaction()))
        every {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), captureLambda(),
            )
        } answers {
            lambda<ErrorPurchaseCallback>().captured(firstArg(), PurchasesError(PurchasesErrorCode.NetworkError))
        }

        recover()
        recover()

        verify(exactly = 2) { callback.onError(match { it.code == PurchasesErrorCode.NetworkError }, false) }
        verify(exactly = 0) { callback.onCompleted(any(), any()) }
        verify(exactly = 2) { mockBillingAbstract.queryAllPurchases(appUserId, any(), any()) }
    }

    @Test
    fun `future checkout timestamp cannot broaden the recovery query`() {
        purchases.recoverPendingPurchase(appUserId, productId, Date(Long.MAX_VALUE), callback)
        verifyRejected()
        verify(exactly = 0) { mockBillingAbstract.queryAllPurchases(any(), any(), any()) }
    }

    private fun recover() = purchases.recoverPendingPurchase(appUserId, productId, startedAt, callback)

    private fun returnPurchases(transactions: List<StoreTransaction>) {
        every { mockBillingAbstract.queryAllPurchases(appUserId, captureLambda(), any()) } answers {
            lambda<(List<StoreTransaction>) -> Unit>().captured(transactions)
        }
    }

    private fun transaction(
        owner: String = appUserId,
        token: String = "pending_purchase_token",
        purchaseTime: Long = System.currentTimeMillis(),
        state: Int = Purchase.PurchaseState.PURCHASED,
    ): StoreTransaction {
        val purchase = stubGooglePurchase(listOf(productId), purchaseTime, token, purchaseState = state)
        val json = JSONObject(purchase.originalJson).put("obfuscatedAccountId", owner.sha256())
        return Purchase(json.toString(), purchase.signature).toStoreTransaction(ProductType.SUBS)
    }

    private fun verifyRejected(code: PurchasesErrorCode = PurchasesErrorCode.PurchaseNotAllowedError) {
        verify(exactly = 1) { callback.onError(match { it.code == code }, false) }
        verify(exactly = 0) {
            callback.onCompleted(any(), any())
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }
}
