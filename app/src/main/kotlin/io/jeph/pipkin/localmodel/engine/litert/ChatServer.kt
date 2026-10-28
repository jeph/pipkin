package io.jeph.pipkin.localmodel.engine.litert

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SamplerParameters
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import io.jeph.pipkin.localmodel.http.HttpRequest
import io.jeph.pipkin.localmodel.http.HttpResponse
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Serves OpenAI Chat Completions from one LiteRT-LM [engine], one request at a time (the main process queues them).
 *
 * LiteRT-LM keeps a conversation's tokens in the model's cache, but it has no way to diff a resent history against
 * it. Pi resends everything on every request, so this keeps one live [Conversation] and the [Transcript] of what it
 * holds, and sends only the new tail when a request extends it (see [planTurn]); anything else starts a new
 * conversation seeded with the request's history, which the model then reads again in full.
 */
internal class ChatServer(
  private val engine: Engine,
  private val apiKey: String,
  private val modelAlias: String,
  /** The cache size the engine was created with; a reply that fills it ends with `finish_reason` "length". */
  private val maxNumTokens: Int,
  /** The model's own sampling, which a request's `temperature` adjusts; null when the model doesn't say. */
  private val sampling: SamplerParameters?,
) {
  private val busy = AtomicBoolean(false)

  // Only the request holding [busy] touches these.
  private var conversation: Conversation? = null
  private var transcript: Transcript? = null

  fun handle(request: HttpRequest, response: HttpResponse) {
    if (request.method == "GET" && request.path == "/health") {
      response.respondJson(STATUS_OK, """{"status":"ok"}""")
      return
    }
    if (request.bearer != apiKey) {
      response.error(STATUS_UNAUTHORIZED, "Invalid API key", "authentication_error")
      return
    }
    if (request.method != "POST" || request.path != "/v1/chat/completions") {
      response.error(STATUS_NOT_FOUND, "No route for ${request.method} ${request.path}", "not_found_error")
      return
    }
    if (!busy.compareAndSet(false, true)) {
      response.error(STATUS_BUSY, "The model is answering another request", "server_busy")
      return
    }
    try {
      complete(request, response)
    } finally {
      busy.set(false)
    }
  }

  private fun complete(request: HttpRequest, response: HttpResponse) {
    val (chat, plan) = try {
      val chat = parseChatRequest(request.body.decodeToString())
      chat to planTurn(transcript, chat)
    } catch (failure: BadRequestException) {
      response.error(STATUS_BAD_REQUEST, failure.message.orEmpty(), "invalid_request_error")
      return
    }
    val started = SystemClock.elapsedRealtime()
    val events = ReplyEvents()
    // The main process hangs up when Pi stops, which may come while the model is still silently reading the prompt.
    request.onHangUp { events.queue.put(ReplyEvent.HungUp) }
    val turn = try {
      send(chat, plan, events)
    } catch (failure: LiteRtLmJniException) {
      refuse(response, failure)
      return
    } catch (failure: IllegalStateException) {
      refuse(response, failure)
      return
    }
    answer(chat, turn, events, response)
    Log.i(TAG, "Request done in ${SystemClock.elapsedRealtime() - started} ms")
  }

  /** Answers a request whose generation couldn't start; the conversation may be half-built, so it goes. */
  private fun refuse(response: HttpResponse, failure: RuntimeException) {
    Log.w(TAG, "Generation couldn't start", failure)
    drop()
    response.error(STATUS_SERVER_ERROR, failure.message.orEmpty())
  }

  /** A generation under way on [conversation], which held [heldBefore] tokens before it, null if it's new. */
  private class Turn(val conversation: Conversation, val heldBefore: Int?)

  private fun send(chat: ChatRequest, plan: TurnPlan, events: ReplyEvents): Turn {
    val live = conversationFor(chat.setup, plan)
    val heldBefore = if (plan is TurnPlan.Continue) live.getTokenCount() else null
    live.sendMessageAsync(
      message = inputMessage(plan.input),
      callback = events,
      maxOutputToken = chat.maxTokens,
      thinkingConfig = if (chat.thinking) null else ThinkingConfig(enableThinking = false),
    )
    return Turn(live, heldBefore)
  }

  private fun answer(chat: ChatRequest, turn: Turn, events: ReplyEvents, response: HttpResponse) {
    val reply = Reply { "call_" + randomId() }
    val completion = Completion("chatcmpl-" + randomId(), System.currentTimeMillis() / MILLIS_PER_SECOND, modelAlias)
    if (chat.stream) response.stream(STATUS_OK, HttpResponse.EVENT_STREAM)
    val failure = try {
      relay(events, reply, completion, response.takeIf { chat.stream })
    } catch (disconnected: IOException) {
      abandon(turn.conversation, events)
      throw disconnected
    }
    if (failure != null) {
      Log.w(TAG, "Generation failed", failure)
      drop()
      val message = failure.message.orEmpty()
      if (chat.stream) response.write(Completion.error(message)) else response.error(STATUS_SERVER_ERROR, message)
      return
    }
    transcript = Transcript(chat.setup, chat.messages + reply.message())
    val usage = usage(turn.conversation, turn.heldBefore)
    val lengthReached = usage != null &&
      (chat.maxTokens?.let { usage.completion >= it } == true || usage.prompt + usage.completion >= maxNumTokens)
    val finishReason = reply.finishReason(lengthReached)
    Log.i(TAG, "Replied: $usage, finish $finishReason")
    if (!chat.stream) {
      response.respondJson(STATUS_OK, completion.response(reply, finishReason, usage))
      return
    }
    response.write(completion.chunk(JSONObject(), finishReason))
    if (chat.includeUsage && usage != null) response.write(completion.usageChunk(usage))
    response.write(Completion.DONE)
  }

  /** The conversation [plan] runs on: the live one, or a new one seeded with the request's history. */
  private fun conversationFor(setup: ConversationSetup, plan: TurnPlan): Conversation = when (plan) {
    is TurnPlan.Continue -> {
      // A transcript only exists alongside the conversation that holds it.
      val live = checkNotNull(conversation) { "No live conversation for the transcript" }
      Log.i(
        TAG,
        "Reusing the conversation: ${live.getTokenCount()} tokens cached, sending ${plan.input.size} message(s)",
      )
      live
    }

    is TurnPlan.Rebuild -> {
      drop()
      Log.i(TAG, "New conversation with ${plan.history.size} earlier message(s) and ${setup.tools.size} tool(s)")
      engine.createConversation(
        ConversationConfig(
          systemInstruction = setup.system?.let { Contents.of(it) },
          initialMessages = historyMessages(plan.history),
          tools = setup.tools.map { tool(FunctionTool(it)) },
          samplerConfig = setup.temperature?.let(::sampler),
          automaticToolCalling = false,
        ),
      ).also { conversation = it }
    }
  }

  private fun sampler(temperature: Double): SamplerConfig? {
    val model = sampling ?: return null
    return SamplerConfig(
      topK = model.topK.coerceAtLeast(1),
      topP = model.topP.toDouble().coerceIn(0.0, 1.0),
      temperature = temperature.coerceAtLeast(0.0),
    )
  }

  /**
   * Streams the reply's deltas to [stream] until LiteRT-LM is done, and returns its failure if it failed. Throws
   * [IOException] when the client hangs up, as a failed write would.
   */
  private fun relay(events: ReplyEvents, reply: Reply, completion: Completion, stream: HttpResponse?): Throwable? {
    while (true) {
      when (val event = events.queue.take()) {
        ReplyEvent.HungUp -> throw IOException("The client hung up")

        is ReplyEvent.Piece -> {
          val message = event.message
          val delta = reply.add(
            textPiece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text },
            reasoningPiece = message.channels.values.joinToString(""),
            toolCalls = message.toolCalls.map { it.name to argumentsJson(it.arguments) },
          )
          if (delta != null) stream?.write(completion.chunk(delta))
        }

        ReplyEvent.Done -> return null

        is ReplyEvent.Failed -> return event.error
      }
    }
  }

  /**
   * Token counts for the turn: everything the conversation holds after it, of which LiteRT-LM's benchmark says how
   * many it wrote. A new conversation ([heldBefore] null) read its whole prompt. Before a user message, LiteRT-LM drops
   * the last turn's reasoning from the cache by rewinding and reading that turn's messages again, and it only reports
   * the last read; so [heldBefore] counts as cached only when the turn added exactly what it read and wrote. Null if
   * the engine can't tell at all.
   */
  @OptIn(ExperimentalApi::class)
  private fun usage(live: Conversation, heldBefore: Int?): Usage? {
    val benchmark: BenchmarkInfo = try {
      live.getBenchmarkInfo()
    } catch (failure: LiteRtLmJniException) {
      Log.w(TAG, "No benchmark for the reply", failure)
      return null
    }
    val held = live.getTokenCount()
    val completion = benchmark.lastDecodeTokenCount
    val cached = when (heldBefore) {
      null -> 0
      else -> heldBefore.takeIf { held == it + benchmark.lastPrefillTokenCount + completion }
    }
    Log.d(TAG, "Tokens: held $heldBefore before, $held after; $benchmark")
    return Usage(prompt = held - completion, completion = completion, cached = cached)
  }

  /**
   * Stops the generation of a client that hung up. LiteRT-LM can't roll a cancelled turn out of its cache, so the
   * conversation is closed once the generation stops, and the next request starts a new one.
   */
  private fun abandon(live: Conversation, events: ReplyEvents) {
    val started = SystemClock.elapsedRealtime()
    live.cancelProcess()
    val deadline = started + CANCEL_TIMEOUT_MILLIS
    while (true) {
      val left = deadline - SystemClock.elapsedRealtime()
      val event = if (left > 0) events.queue.poll(left, TimeUnit.MILLISECONDS) else null
      if (event == null) {
        // Closing a conversation under a running task could crash the process; leave it to the process's end.
        Log.w(TAG, "Generation didn't stop within $CANCEL_TIMEOUT_MILLIS ms of the client leaving")
        conversation = null
        transcript = null
        return
      }
      if (event is ReplyEvent.Done || event is ReplyEvent.Failed) break
    }
    Log.i(TAG, "Client left; generation stopped in ${SystemClock.elapsedRealtime() - started} ms")
    drop()
  }

  private fun drop() {
    conversation?.takeIf { it.isAlive }?.close()
    conversation = null
    transcript = null
  }

  private fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

  private sealed interface ReplyEvent {
    class Piece(val message: Message) : ReplyEvent
    data object Done : ReplyEvent
    data object HungUp : ReplyEvent
    class Failed(val error: Throwable) : ReplyEvent
  }

  /** LiteRT-LM calls back on its own thread; the request's thread takes the events from [queue]. */
  private class ReplyEvents : MessageCallback {
    val queue = LinkedBlockingQueue<ReplyEvent>()

    override fun onMessage(message: Message) = queue.put(ReplyEvent.Piece(message))

    override fun onDone() = queue.put(ReplyEvent.Done)

    override fun onError(throwable: Throwable) = queue.put(ReplyEvent.Failed(throwable))
  }

  private companion object {
    const val TAG = "PipkinLiteRt"
    const val STATUS_OK = 200
    const val STATUS_BAD_REQUEST = 400
    const val STATUS_UNAUTHORIZED = 401
    const val STATUS_NOT_FOUND = 404
    const val STATUS_SERVER_ERROR = 500
    const val STATUS_BUSY = 503
    const val MILLIS_PER_SECOND = 1000
    const val CANCEL_TIMEOUT_MILLIS = 30_000L
  }
}
