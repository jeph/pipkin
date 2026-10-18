package io.jeph.pipkin.agent

import android.content.res.Resources
import androidx.annotation.StringRes
import io.jeph.pipkin.R

@get:StringRes
val NoticeKind.text: Int
  get() = when (this) {
    NoticeKind.Retrying -> R.string.chat_notice_retrying
    NoticeKind.Compacting -> R.string.chat_notice_compacting
    NoticeKind.Compacted -> R.string.chat_notice_compacted
  }

/** The error's line in the chat and in its notification: Pi's reason, or Pipkin's when Pi gave none. */
fun ErrorCause.text(resources: Resources): String = when (this) {
  is ErrorCause.Request -> message ?: resources.getString(R.string.chat_error_request_failed)
  is ErrorCause.Compaction -> message ?: resources.getString(R.string.chat_error_compaction_failed)
  is ErrorCause.Extension -> resources.getString(R.string.chat_error_extension, path, error)
}
