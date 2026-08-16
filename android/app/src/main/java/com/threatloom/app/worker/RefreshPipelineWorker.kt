package com.threatloom.app.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.threatloom.app.domain.service.CostConfirmationGate
import com.threatloom.app.domain.usecase.ActualCostInfo
import com.threatloom.app.domain.usecase.CostEstimate
import com.threatloom.app.domain.usecase.PipelineProgress
import com.threatloom.app.domain.usecase.ProcessCustomUrlsUseCase
import com.threatloom.app.domain.usecase.RunPipelineUseCase
import com.threatloom.app.notification.RefreshNotificationHelper
import com.threatloom.app.util.AppEvent
import com.threatloom.app.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

@HiltWorker
class RefreshPipelineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val runPipelineUseCase: RunPipelineUseCase,
    private val processCustomUrlsUseCase: ProcessCustomUrlsUseCase,
    private val costConfirmationGate: CostConfirmationGate,
    private val appEvent: AppEvent,
    private val appLogger: AppLogger
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "RefreshPipelineWorker"

        const val WORK_NAME = "threatloom_pipeline_work"

        const val KEY_MODE = "mode"
        const val MODE_REFRESH = "refresh"
        const val MODE_SINCE_LAST = "refresh_since_last"
        const val MODE_CUSTOM_URLS = "custom_urls"

        const val KEY_LOOKBACK_DAYS = "lookback_days"
        const val KEY_URLS = "urls"

        const val KEY_STAGE = "stage"
        const val KEY_DETAIL = "detail"
        const val KEY_FRACTION = "fraction"

        const val STAGE_ACTUAL_COST = "actual_cost"
        const val KEY_ACTUAL_COST_ARTICLE_COUNT = "actual_cost_article_count"
        const val KEY_ACTUAL_COST_AMOUNT = "actual_cost_amount"
        const val KEY_ACTUAL_COST_MODEL = "actual_cost_model"

        const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        setForeground(buildForegroundInfo("Starting…", indeterminate = true))
        appEvent.setPipelineRunning(true)

        var confirmDeferred: CompletableDeferred<Boolean>? = null
        try {
            val onProgress: suspend (PipelineProgress) -> Unit = { progress ->
                val text = if (progress.stage == "confirm") {
                    "Cost confirmation needed — tap to review"
                } else {
                    progress.detail
                }
                setProgress(
                    workDataOf(
                        KEY_STAGE to progress.stage,
                        KEY_DETAIL to progress.detail,
                        KEY_FRACTION to progress.overallFraction
                    )
                )
                when {
                    progress.stage == "confirm" -> notify(text, current = 0, total = 0, indeterminate = false)
                    progress.total > 0 -> notify(text, current = progress.current, total = progress.total, indeterminate = false)
                    else -> notify(text, current = 0, total = 0, indeterminate = true)
                }
            }
            val onConfirmCost: suspend (CostEstimate) -> CompletableDeferred<Boolean> = { estimate ->
                val deferred = costConfirmationGate.beginRequest(estimate)
                confirmDeferred = deferred
                deferred
            }
            val onActualCost: (ActualCostInfo) -> Unit = { info ->
                setProgressAsync(
                    workDataOf(
                        KEY_STAGE to STAGE_ACTUAL_COST,
                        KEY_ACTUAL_COST_ARTICLE_COUNT to info.articleCount,
                        KEY_ACTUAL_COST_AMOUNT to info.actualCost,
                        KEY_ACTUAL_COST_MODEL to info.modelName
                    )
                )
            }
            val onRateLimited: () -> Unit = {
                appEvent.notifyRateLimited()
            }

            when (val mode = inputData.getString(KEY_MODE)) {
                MODE_REFRESH -> runPipelineUseCase(
                    lookbackDays = inputData.getInt(KEY_LOOKBACK_DAYS, 1),
                    onProgress = onProgress,
                    onConfirmCost = onConfirmCost,
                    onActualCost = onActualCost,
                    onRateLimited = onRateLimited
                )
                MODE_SINCE_LAST -> runPipelineUseCase(
                    lookbackDays = 0,
                    onProgress = onProgress,
                    onConfirmCost = onConfirmCost,
                    onActualCost = onActualCost,
                    onRateLimited = onRateLimited
                )
                MODE_CUSTOM_URLS -> processCustomUrlsUseCase(
                    urls = inputData.getStringArray(KEY_URLS)?.toList() ?: emptyList(),
                    onProgress = onProgress,
                    onConfirmCost = onConfirmCost,
                    onActualCost = onActualCost,
                    onRateLimited = onRateLimited
                )
                else -> throw IllegalArgumentException("Unknown pipeline mode: $mode")
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogger.e(TAG, "Pipeline failed", e)
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        } finally {
            confirmDeferred?.let { costConfirmationGate.cancelIfPending(it) }
            appEvent.setPipelineRunning(false)
        }
    }

    private fun buildForegroundInfo(text: String, current: Int = 0, total: Int = 0, indeterminate: Boolean = false): ForegroundInfo {
        val notification = RefreshNotificationHelper.build(applicationContext, id, text, current, total, indeterminate)
        return ForegroundInfo(RefreshNotificationHelper.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notify(text: String, current: Int, total: Int, indeterminate: Boolean) {
        val notification = RefreshNotificationHelper.build(applicationContext, id, text, current, total, indeterminate)
        NotificationManagerCompat.from(applicationContext).notify(RefreshNotificationHelper.NOTIFICATION_ID, notification)
    }
}
