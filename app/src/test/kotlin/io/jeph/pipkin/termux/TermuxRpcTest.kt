package io.jeph.pipkin.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TermuxRpcTest {
  @Test
  fun explicitRejectionCanBeJournaledAsRejected() {
    val rejection = assertThrows(RuntimeRejection::class.java) {
      rpcResult(
        strictObject(
          """{"error":{"code":-32000,"message":"Generation already exists"}}""".toByteArray(),
        ),
      )
    }
    assertEquals("Generation already exists", rejection.reason)
  }

  @Test
  fun uncertainExecutionIsNeverConvertedToRejection() {
    val failure = assertThrows(RuntimeFailure::class.java) {
      rpcResult(
        strictObject(
          """{"error":{"code":-32001,"message":"Receipt commit failed"}}""".toByteArray(),
        ),
      )
    }
    assertEquals(RuntimeFailureKind.Transport, failure.kind)
  }

  @Test
  fun ambiguousResultAndErrorCannotMarkAReceiptAccepted() {
    val failure = assertThrows(RuntimeFailure::class.java) {
      rpcResult(
        strictObject(
          """{"result":{},"error":{"code":-32000,"message":"Rejected"}}""".toByteArray(),
        ),
      )
    }
    assertEquals(RuntimeFailureKind.Compatibility, failure.kind)
  }
}
