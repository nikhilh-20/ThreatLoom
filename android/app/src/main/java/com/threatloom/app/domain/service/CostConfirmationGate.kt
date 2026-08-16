package com.threatloom.app.domain.service

import com.threatloom.app.domain.usecase.CostEstimate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the long-lived [com.threatloom.app.worker.RefreshPipelineWorker] (which outlives any
 * single DashboardViewModel instance) with whichever screen is currently on-screen to show the
 * cost-confirmation dialog. The worker calls [beginRequest] and suspends on the returned
 * deferred; the UI calls [approve]/[decline] when the user responds.
 */
@Singleton
class CostConfirmationGate @Inject constructor() {

    private var currentDeferred: CompletableDeferred<Boolean>? = null

    private val _pending = MutableStateFlow<CostEstimate?>(null)
    val pending: StateFlow<CostEstimate?> = _pending.asStateFlow()

    fun beginRequest(estimate: CostEstimate): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        currentDeferred = deferred
        _pending.value = estimate
        return deferred
    }

    fun approve() = resolve(true)

    fun decline() = resolve(false)

    private fun resolve(result: Boolean) {
        currentDeferred?.complete(result)
        currentDeferred = null
        _pending.value = null
    }

    /**
     * Called from the worker's `finally` block so a cancel while the dialog is showing doesn't
     * leave a zombie dialog bound to a deferred nobody will ever complete. No-op if a newer
     * request has already replaced this one.
     */
    fun cancelIfPending(deferred: CompletableDeferred<Boolean>) {
        if (currentDeferred === deferred) {
            currentDeferred = null
            _pending.value = null
        }
    }
}
