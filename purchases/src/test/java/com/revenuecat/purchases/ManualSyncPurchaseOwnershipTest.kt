package com.revenuecat.purchases

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.billingclient.api.Purchase
import com.revenuecat.purchases.google.toStoreTransaction
import com.revenuecat.purchases.models.StoreTransaction
import com.revenuecat.purchases.common.sha256
import com.revenuecat.purchases.utils.stubGooglePurchase
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
internal class ManualSyncPurchaseOwnershipTest : BasePurchasesTest() {
    private val productId = "explicit_subscription"

    @Before
    fun configureManualSync() {
        buildPurchases(anonymous = false, autoSync = false)
    }

    @Test
    fun `unsolicited store update does not post a receipt in manual mode`() {
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(transaction("old_subscription")))

        verify(exactly = 0) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
        verify(exactly = 0) { mockEventsManager.flushEvents(any()) }
    }

    @Test
    fun `explicit purchase posts under its starting owner after identity changes`() {
        val purchased = beginPurchase()
        every { mockIdentityManager.currentAppUserID } returns "subsequent_account"
        every { mockIdentityManager.currentUserIsAnonymous() } returns true

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(purchased))

        verifyPosted(purchased, appUserId)
    }

    @Test
    fun `unrelated pending update is not posted alongside explicit purchase`() {
        val purchased = beginPurchase()
        val unrelated = transaction("unrelated_pending_subscription")

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(unrelated, purchased))

        verifyPosted(purchased, appUserId)
        verify(exactly = 0) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                unrelated, any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `matching SKU owned by a different account is not posted`() {
        beginPurchase()
        val unrelated = transaction(productId, owner = "another_purchase_owner")

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(unrelated))

        verifyNoReceiptsPosted()
    }

    @Test
    fun `matching SKU without store account evidence is not posted`() {
        beginPurchase()
        val ambiguous = getMockedStoreTransaction(productId, "ambiguous_token", ProductType.SUBS)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(ambiguous))

        verifyNoReceiptsPosted()
    }

    @Test
    fun `old matching transaction is not mistaken for the new checkout`() {
        beginPurchase()
        val old = transaction(productId, purchaseTime = 1L)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(old))

        verifyNoReceiptsPosted()
    }

    @Test
    fun `future matching transaction is not posted`() {
        beginPurchase()
        val future = transaction(productId, purchaseTime = System.currentTimeMillis() + 60_000L)
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(future))
        verifyNoReceiptsPosted()
    }

    @Test
    fun `completed explicit purchase no longer authorizes subsequent store updates`() {
        val purchased = beginPurchase()
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(purchased))
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(purchased))

        verifyPosted(purchased, appUserId)
    }

    @Test
    fun `reordered multiline result completes the initiating callback`() {
        var completed = 0
        beginPurchase { _, _ -> completed++ }
        val products = listOf("add_on_first", productId)
        mockStoreProduct(products, products, ProductType.SUBS)
        val purchase = transaction(productId, products = products)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(purchase))

        verifyPosted(purchase, appUserId)
        assertThat(completed).isEqualTo(1)
    }

    @Test
    fun `late receipt completion cannot consume a newer callback for the same SKU`() {
        val receiptCompletions = mutableListOf<SuccessfulPurchaseCallback>()
        every {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), captureLambda(), any(),
            )
        } answers { receiptCompletions.add(lambda<SuccessfulPurchaseCallback>().captured); Unit }
        var firstCompleted = 0
        var secondCompleted = 0
        val first = beginPurchase { _, _ -> firstCompleted++ }
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(first))
        capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(
            PurchasesError(PurchasesErrorCode.PurchaseCancelledError),
        )
        val second = beginPurchase { _, _ -> secondCompleted++ }

        receiptCompletions.single()(first, mockInfo)
        assertThat(firstCompleted).isZero()
        assertThat(secondCompleted).isZero()
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(second))
        receiptCompletions.last()(second, mockInfo)

        assertThat(firstCompleted).isZero()
        assertThat(secondCompleted).isEqualTo(1)
    }

    @Test
    fun `failed purchase callback no longer authorizes subsequent store updates`() {
        val purchased = beginPurchase()
        capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(
            PurchasesError(PurchasesErrorCode.PurchaseCancelledError),
        )
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(purchased))

        verify(exactly = 0) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `in-app message completion does not trigger an implicit sync in manual mode`() {
        every { mockBillingAbstract.showInAppMessagesIfNeeded(any(), any(), any()) } answers {
            thirdArg<() -> Unit>()()
        }

        purchases.showInAppMessagesIfNeeded(mockActivity)

        verify(exactly = 0) { mockSyncPurchasesHelper.syncPurchases(any(), any(), any(), any()) }
    }

    @Test
    fun `explicit sync remains available in manual mode`() {
        every { mockSyncPurchasesHelper.syncPurchases(any(), any(), any(), any()) } just Runs

        purchases.syncPurchases()

        verify(exactly = 1) { mockSyncPurchasesHelper.syncPurchases(false, false, any(), any()) }
    }

    @Test
    fun `explicit restore still posts historical purchases in manual mode`() {
        val restored = getMockedStoreTransaction(productId, "historical_restore", ProductType.SUBS)
        every { mockBillingAbstract.queryAllPurchases(appUserId, captureLambda(), any()) } answers {
            lambda<(List<StoreTransaction>) -> Unit>().captured(listOf(restored))
        }

        purchases.restorePurchases(mockk<ReceiveCustomerInfoCallback>(relaxed = true))

        verify(exactly = 1) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                restored, null, null, true, appUserId, PostReceiptInitiationSource.RESTORE,
                false, any(), any(),
            )
        }
    }

    @Test
    fun `automatic sync remains enabled by default`() {
        assertThat(DangerousSettings().autoSyncPurchases).isTrue()
        buildPurchases(anonymous = false)
        mockStoreProduct(listOf(productId), listOf(productId), ProductType.SUBS)
        val unsolicited = transaction(productId)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(listOf(unsolicited))

        verify(exactly = 1) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                unsolicited, any(), any(), false, appUserId, PostReceiptInitiationSource.PURCHASE,
                false, any(), any(),
            )
        }
    }

    private fun beginPurchase(onSuccess: (StoreTransaction?, CustomerInfo) -> Unit = { _, _ -> }): StoreTransaction {
        val product = mockStoreProduct(listOf(productId), listOf(productId), ProductType.SUBS).first()
        purchases.purchaseWith(getPurchaseParams(product.subscriptionOptions!!.first()), onSuccess = onSuccess)
        return transaction(productId)
    }

    private fun transaction(
        sku: String,
        owner: String = appUserId,
        purchaseTime: Long = System.currentTimeMillis(),
        products: List<String> = listOf(sku),
    ): StoreTransaction {
        val purchase = stubGooglePurchase(products, purchaseTime, "token_$sku")
        val json = JSONObject(purchase.originalJson).put("obfuscatedAccountId", owner.sha256())
        return Purchase(json.toString(), purchase.signature).toStoreTransaction(ProductType.SUBS)
    }

    private fun verifyNoReceiptsPosted() {
        verify(exactly = 0) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    private fun verifyPosted(transaction: StoreTransaction, owner: String) {
        verify(exactly = 1) {
            mockPostReceiptHelper.postTransactionAndConsumeIfNeeded(
                transaction, any(), any(), false, owner, PostReceiptInitiationSource.PURCHASE,
                true, any(), any(),
            )
        }
    }
}
