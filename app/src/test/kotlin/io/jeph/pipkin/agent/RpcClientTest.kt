package io.jeph.pipkin.agent

import io.jeph.pipkin.host.HostRejected
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcClientTest {
  private val sent = Channel<JSONObject>(Channel.UNLIMITED)
  private val rpc = RpcClient(send = { sent.send(it) }, timeoutMillis = 200)

  @Test
  fun responsesResolveTheirOwnRequestsInAnyOrder() = runBlocking {
    val state = async { rpc.call("get_state") }
    val model = async { rpc.call("set_model", JSONObject().put("provider", "p").put("modelId", "m")) }
    val first = sent.receive()
    val second = sent.receive()

    assertEquals("get_state", first.getString("type"))
    assertEquals("r1", first.getString("id"))
    assertEquals("set_model", second.getString("type"))
    assertEquals("r2", second.getString("id"))
    assertEquals("m", second.getString("modelId"))

    rpc.onResponse(response("r2", "set_model", JSONObject().put("id", "m")))
    rpc.onResponse(response("r1", "get_state", JSONObject().put("messages", JSONArray())))

    assertEquals("m", model.await().getString("id"))
    assertTrue(state.await().has("messages"))
  }

  @Test
  fun missingDataIsAnEmptyObject() = runBlocking {
    val abort = async { rpc.call("abort") }
    rpc.onResponse(JSONObject().put("id", sent.receive().getString("id")).put("type", "response").put("success", true))

    assertEquals(0, abort.await().length())
  }

  @Test
  fun unsuccessfulResponseThrowsRpcError() = runBlocking {
    val call = async { rpcError { rpc.call("set_model") } }
    val id = sent.receive().getString("id")
    rpc.onResponse(
      JSONObject().put("id", id).put("type", "response").put("success", false).put("error", "Model not found: x/y"),
    )

    val error = call.await()
    assertEquals("set_model", error.command)
    assertEquals("Model not found: x/y", error.error)
  }

  @Test
  fun unansweredRequestTimesOutAndALateResponseIsIgnored() = runBlocking {
    val error = rpcError { rpc.call("get_messages") }
    val id = sent.receive().getString("id")
    rpc.onResponse(response(id, "get_messages", JSONObject()))

    assertEquals("get_messages", error.command)
  }

  @Test
  fun closeFailsWaitingAndLaterCallsWithTheFirstReason() = runBlocking {
    val waiting = async { rpcError { rpc.call("get_state") } }
    sent.receive()
    val refusal = HostRejected("pi_version", "Pi 1.0.0 is installed")
    rpc.close(refusal.error, refusal)
    rpc.close(RpcClient.NOT_RUNNING)

    assertSame(refusal, waiting.await().cause)
    val later = rpcError { rpc.call("get_messages") }
    assertEquals("get_messages", later.command)
    assertSame(refusal, later.cause)
  }

  @Test
  fun sendFailureMeansNotRunning() = runBlocking {
    val broken = RpcClient(send = { throw IOException("Socket closed") })

    val error = rpcError { broken.call("prompt") }

    assertEquals(RpcClient.NOT_RUNNING, error.error)
    assertTrue(error.cause is IOException)
  }

  private fun response(id: String, command: String, data: JSONObject) = JSONObject()
    .put("id", id)
    .put("type", "response")
    .put("command", command)
    .put("success", true)
    .put("data", data)

  private suspend fun rpcError(block: suspend () -> Unit): RpcError {
    try {
      block()
    } catch (error: RpcError) {
      return error
    }
    throw AssertionError("Expected RpcError")
  }
}
