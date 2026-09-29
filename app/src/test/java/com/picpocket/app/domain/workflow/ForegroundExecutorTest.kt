package com.picpocket.app.domain.workflow

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.domain.workflow.engine.AppForegroundExecutor
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.Artifact
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ForegroundExecutorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    private class Foreground(
        private val testDispatcher: CoroutineDispatcher,
    ) : AppForegroundExecutor(ApplicationProvider.getApplicationContext()) {
        override val isForeground = true
        override val dispatcher get() = testDispatcher
    }

    @Test
    fun `run returns a failure when not in the foreground`() = runTest(dispatcher) {
        val executor = AppForegroundExecutor(app)
        assertTrue(!executor.isForeground)
        val result = executor.run { ActionResult.Success(Artifact("x", "y")) }
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `foreground actions are serialized`() = runTest(dispatcher) {
        val executor = Foreground(dispatcher)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val body: suspend () -> ActionResult = {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            delay(20)
            active.decrementAndGet()
            ActionResult.Success(Artifact("x", "y"))
        }
        (1..4).map { async { executor.run(body) } }.awaitAll()
        assertEquals("only one foreground action at a time", 1, maxActive.get())
    }
}
