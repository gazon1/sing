package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.proposals.domain.model.ReadResult
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertIs

@Tag("fast")
class ReadEstimateNegativeTest {
    @Test
    fun `negative estimate is rejected up front`() {
        assertIs<ReadResult.Rejected>(TaskValue.read("EstimateMinutes", "-30"))
    }
}
