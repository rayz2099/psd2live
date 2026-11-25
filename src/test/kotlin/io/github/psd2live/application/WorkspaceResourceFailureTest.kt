package io.github.psd2live.application

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class WorkspaceResourceFailureTest {
    @Test fun memoryFailuresAreClassifiedByTypeEvenWhenWrappedOrWithoutAMessage() {
        for (failure in listOf(OutOfMemoryError(), java.util.concurrent.ExecutionException(OutOfMemoryError("Java heap space")))) {
            val classified = WorkspaceFailure.from(failure)
            assertEquals("out_of_memory", classified.code)
            assertTrue(classified.message.contains("Not enough memory"))
        }
        assertEquals("invalid_state", WorkspaceFailure.from(IllegalStateException("Java heap space")).code)
        assertEquals("stack_overflow", WorkspaceFailure.from(StackOverflowError()).code)
        assertFailsWith<IllegalArgumentException> { WorkspaceFailure.from(CancellationException()) }
    }

    @Test fun aResourceFailureTerminatesTheJobAndADurableCommitStillWins() = runBlocking {
        WorkspaceJobs(Dispatchers.Unconfined).use { jobs ->
            val failed = jobs.start("import", null, "initial") { throw OutOfMemoryError() }
            val terminal = jobs.wait(failed.id)
            assertEquals(WorkspaceJobStatus.FAILED, terminal.status)
            assertEquals("out_of_memory", terminal.error!!.code)
            val output = WorkspaceOperationOutput(JsonObject(emptyMap()))
            val committed = jobs.start("edit", "project", "state") {
                currentCoroutineContext()[WorkspaceJobCompletion]!!.committed(output)
                throw OutOfMemoryError()
            }
            assertEquals(WorkspaceJobStatus.COMPLETED, jobs.wait(committed.id).status)
            assertEquals(output, jobs.wait(committed.id).result)
        }
    }

    /** 校验 WorkspaceFailures.from 产出的全部失败类型均通过 WorkspaceResultSchema.failure 及 job_wait/job_list 契约。 */
    @Test fun everyWorkspaceFailureConformsToFailureAndJobResultSchemas() = runBlocking {
        val sampleFailures = listOf<Throwable>(
            OutOfMemoryError("Java heap space"),
            StackOverflowError(),
            WorkspaceNotGenerated(),
            WorkspaceBatchEditException(1, "layer_mesh_rebuild", WorkspaceNotGenerated()),
            WorkspaceGeneratedCopyEdit("copy_brow", "orig_brow"),
            WorkspaceBatchEditException(2, "keyform_apply", WorkspaceGeneratedCopyEdit("copy_brow", "orig_brow")),
            WorkspaceCheckTimeout(),
            WorkspaceValidationException("request.state", "missing"),
            WorkspaceOutputContractFailure("job_wait", WorkspaceValidationException("result.error", "bad")),
            WorkspaceBatchEditException(0, "canvas_warp", IllegalArgumentException("bad warp")),
            WorkspaceTileCollision(listOf("l1", "l2")),
            WorkspaceConflict("s1", "s2"),
            WorkspaceProjectConflict("p1", "p2"),
            WorkspaceRequestReuse(),
            WorkspaceUnsavedChanges(),
            WorkspaceBusy(),
            java.nio.file.AccessDeniedException("/tmp/denied"),
            java.io.IOException("disk full"),
            IllegalArgumentException("bad arg"),
            IllegalStateException("bad state"),
            RuntimeException("boom"),
        )
        for (throwable in sampleFailures) {
            val failure = WorkspaceFailure.from(throwable)
            validateOperationSchema(failure.toJson(), WorkspaceResultSchema.failure, "error(${failure.code})")
        }
        WorkspaceJobs(Dispatchers.Unconfined).use { jobs ->
            val job = jobs.start("project_export_model", "p1", "s1") { throw OutOfMemoryError("heap") }
            val waited = jobs.wait(job.id)
            val waitPayload = waited.toJson()
            val waitSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("job_wait"))
            validateOperationSchema(waitPayload, waitSchema, "job_wait")

            val matching = jobs.list("p1")
            val listPayload = buildJsonObject {
                put("items", JsonArray(matching.map { it.toJson(includeResult = false) }))
                put("total", matching.size)
            }
            val listSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("job_list"))
            validateOperationSchema(listPayload, listSchema, "job_list")
        }
    }
}
