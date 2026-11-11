package kz.tungo.sdk.kotlin.resources

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kz.tungo.sdk.kotlin.RequestOptions
import kz.tungo.sdk.kotlin.errors.TungoWaitTimeoutException
import kz.tungo.sdk.kotlin.errors.TungoValidationException
import kz.tungo.sdk.kotlin.internal.HttpEngine
import kz.tungo.sdk.kotlin.models.AutomationRunDetail
import kz.tungo.sdk.kotlin.models.AutomationScenarioRunInput
import kz.tungo.sdk.kotlin.models.BatchJob

/** States in which an automation run no longer changes, per the state enum of the contract. */
private val terminalRunStates: Set<String> = setOf("done", "error", "skipped", "cancelled")

/**
 * Polls an automation run until it reaches a final state (done, error, skipped, cancelled). An unknown state
 * is not final: polling goes on. The polling interval grows from 1 s to 5 s.
 *
 * @param timeout how long to wait overall
 * @throws TungoWaitTimeoutException when the run is not finished in time (a local deadline, not an HTTP error)
 */
public suspend fun AutomationsResource.waitForRun(
    id: String,
    companyId: String? = null,
    timeout: Duration = 10.minutes,
    requestOptions: RequestOptions = RequestOptions.Default,
): AutomationRunDetail = pollUntil(engine, timeout, "automation run $id", { getRun(id, companyId, requestOptions) }) {
    it.state.value in terminalRunStates
}

/**
 * Starts a scenario manually and waits until its run reaches a final state.
 *
 * @param id the scenario id
 * @throws TungoWaitTimeoutException when the run is not finished in time
 */
public suspend fun AutomationsResource.runScenarioAndWait(
    id: String,
    body: AutomationScenarioRunInput? = null,
    companyId: String? = null,
    timeout: Duration = 10.minutes,
    requestOptions: RequestOptions = RequestOptions.Default,
): AutomationRunDetail {
    val run = runScenario(id, body, companyId, requestOptions)
    return waitForRun(run.id, companyId, timeout, requestOptions)
}

/**
 * Polls a batch job until it stops running. The polling interval grows from 1 s to 5 s.
 *
 * @param timeout how long to wait overall
 * @throws TungoWaitTimeoutException when the job is still running at the deadline
 */
public suspend fun BatchesResource.waitForBatch(
    id: String,
    companyId: String? = null,
    timeout: Duration = 10.minutes,
    requestOptions: RequestOptions = RequestOptions.Default,
): BatchJob = pollUntil(engine, timeout, "batch job $id", { get(id, companyId, requestOptions) }) { !it.running }

internal suspend fun <T> pollUntil(engine: HttpEngine, timeout: Duration, what: String, fetch: suspend () -> T, done: (T) -> Boolean): T {
    if (!timeout.isPositive()) throw TungoValidationException("timeout must be positive")
    val now = engine.options.hooks.nowMillis
    val deadline = now() + timeout.inWholeMilliseconds
    var interval = 1000L
    while (true) {
        val value = fetch()
        if (done(value)) return value
        val left = deadline - now()
        if (left <= 0) throw TungoWaitTimeoutException("Timed out after $timeout waiting for $what")
        engine.sleep(minOf(interval, left))
        interval = minOf(interval * 3 / 2, 5000L)
    }
}
