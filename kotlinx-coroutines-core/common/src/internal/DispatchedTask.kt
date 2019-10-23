/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlinx.coroutines.internal.*
import kotlin.coroutines.*
import kotlin.jvm.*

@PublishedApi internal const val MODE_ATOMIC_DEFAULT = 0 // schedule non-cancellable dispatch for suspendCoroutine
@PublishedApi internal const val MODE_CANCELLABLE = 1    // schedule cancellable dispatch for suspendCancellableCoroutine
@PublishedApi internal const val MODE_UNDISPATCHED = 2   // when the thread is right, but need to mark it with current coroutine

internal val Int.isCancellableMode get() = this == MODE_CANCELLABLE
internal val Int.isDispatchedMode get() = this == MODE_ATOMIC_DEFAULT || this == MODE_CANCELLABLE

internal abstract class DispatchedTask<in T>(
    @JvmField public var resumeMode: Int
) : SchedulerTask() {
    internal abstract val delegate: Continuation<T>

    internal abstract fun takeState(): Any?

    internal open fun cancelResult(state: Any?, cause: Throwable) {}

    @Suppress("UNCHECKED_CAST")
    internal open fun <T> getSuccessfulResult(state: Any?): T =
        state as T

    internal fun getExceptionalResult(state: Any?): Throwable? =
        (state as? CompletedExceptionally)?.cause

    public final override fun run() {
        val taskContext = this.taskContext
        var fatalException: Throwable? = null
        try {
            val delegate = delegate.useLocal() as DispatchedContinuation<T> // cast must succeed
            val continuation = delegate.continuation
            val context = continuation.context
            val state = takeState() // NOTE: Must take state in any case, even if cancelled
            withCoroutineContext(context, delegate.countOrElement) {
                val exception = getExceptionalResult(state)
                val job = if (resumeMode.isCancellableMode) context[Job] else null
                /*
                 * Check whether continuation was originally resumed with an exception.
                 * If so, it dominates cancellation, otherwise the original exception
                 * will be silently lost.
                 */
                if (exception == null && job != null && !job.isActive) {
                    val cause = job.getCancellationException()
                    cancelResult(state, cause)
                    continuation.resumeWithStackTrace(cause)
                } else {
                    if (exception != null) continuation.resumeWithException(exception)
                    else continuation.resume(getSuccessfulResult(state))
                }
            }
        } catch (e: Throwable) {
            // This instead of runCatching to have nicer stacktrace and debug experience
            fatalException = e
        } finally {
            val result = runCatching { taskContext.afterTask() }
            handleFatalException(fatalException, result.exceptionOrNull())
        }
    }

    /**
     * Machinery that handles fatal exceptions in kotlinx.coroutines.
     * There are two kinds of fatal exceptions:
     *
     * 1) Exceptions from kotlinx.coroutines code. Such exceptions indicate that either
     *    the library or the compiler has a bug that breaks internal invariants.
     *    They usually have specific workarounds, but require careful study of the cause and should
     *    be reported to the maintainers and fixed on the library's side anyway.
     *
     * 2) Exceptions from [ThreadContextElement.updateThreadContext] and [ThreadContextElement.restoreThreadContext].
     *    While a user code can trigger such exception by providing an improper implementation of [ThreadContextElement],
     *    we can't ignore it because it may leave coroutine in the inconsistent state.
     *    If you encounter such exception, you can either disable this context element or wrap it into
     *    another context element that catches all exceptions and handles it in the application specific manner.
     *
     * Fatal exception handling can be intercepted with [CoroutineExceptionHandler] element in the context of
     * a failed coroutine, but such exceptions should be reported anyway.
     */
    internal fun handleFatalException(exception: Throwable?, finallyException: Throwable?) {
        if (exception === null && finallyException === null) return
        if (exception !== null && finallyException !== null) {
            exception.addSuppressedThrowable(finallyException)
        }

        val cause = exception ?: finallyException
        val reason = CoroutinesInternalError("Fatal exception in coroutines machinery for $this. " +
                "Please read KDoc to 'handleFatalException' method and report this incident to maintainers", cause!!)
        handleCoroutineException(this.delegate.context, reason)
    }
}

internal fun <T> CancellableContinuationImpl<T>.dispatch(mode: Int) {
    val delegate = this.delegate
    val local = delegate.asLocalOrNull() // go to shareableResume when called from wrong worker
    if (mode.isDispatchedMode && local is DispatchedContinuation<*> && mode.isCancellableMode == resumeMode.isCancellableMode) {
        // dispatch directly using this instance's Runnable implementation
        val dispatcher = local.dispatcher
        val context = local.context
        if (dispatcher.isDispatchNeeded(context)) {
            dispatcher.dispatch(context, this)
        } else {
            resumeUnconfined()
        }
    } else {
        shareableResume(delegate, mode)
    }
}

internal fun <T> CancellableContinuationImpl<T>.resumeImpl(delegate: Continuation<T>, useMode: Int) {
    val state = takeState()
    val exception = getExceptionalResult(state)?.let { recoverStackTrace(it, delegate) }
    val result = if (exception != null) Result.failure(exception) else Result.success(state as T)
    when (useMode) {
        MODE_ATOMIC_DEFAULT -> delegate.resumeWith(result)
        MODE_CANCELLABLE -> delegate.resumeCancellableWith(result)
        MODE_UNDISPATCHED -> (delegate as DispatchedContinuation).resumeUndispatchedWith(result)
        else -> error("Invalid mode $useMode")
    }
}

private fun <T> CancellableContinuationImpl<T>.resumeUnconfined() {
    val eventLoop = ThreadLocalEventLoop.eventLoop
    if (eventLoop.isUnconfinedLoopActive) {
        // When unconfined loop is active -- dispatch continuation for execution to avoid stack overflow
        eventLoop.dispatchUnconfined(this)
    } else {
        // Was not active -- run event loop until all unconfined tasks are executed
        runUnconfinedEventLoop(eventLoop) {
            resumeImpl(delegate.useLocal(), MODE_UNDISPATCHED)
        }
    }
}

internal inline fun DispatchedTask<*>.runUnconfinedEventLoop(
    eventLoop: EventLoop,
    block: () -> Unit
) {
    eventLoop.incrementUseCount(unconfined = true)
    try {
        block()
        while (true) {
            // break when all unconfined continuations where executed
            if (!eventLoop.processUnconfinedEvent()) break
        }
    } catch (e: Throwable) {
        /*
         * This exception doesn't happen normally, only if we have a bug in implementation.
         * Report it as a fatal exception.
         */
        handleFatalException(e, null)
    } finally {
        eventLoop.decrementUseCount(unconfined = true)
    }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Continuation<*>.resumeWithStackTrace(exception: Throwable) {
    resumeWith(Result.failure(recoverStackTrace(exception, this)))
}