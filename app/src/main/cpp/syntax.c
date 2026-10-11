// JNI bridge between io.jeph.pipkin.syntax.TreeSitter and tree-sitter's C API. Text crosses as
// UTF-16, which tree-sitter parses natively, so a byte offset is always twice a Java char index.

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <tree_sitter/api.h>

#define GRAMMARS(X) \
  X(bash)           \
  X(c)              \
  X(c_sharp)        \
  X(cpp)            \
  X(dart)           \
  X(go)             \
  X(java)           \
  X(javascript)     \
  X(json)           \
  X(kotlin)         \
  X(perl)           \
  X(php)            \
  X(python)         \
  X(ruby)           \
  X(rust)           \
  X(swift)          \
  X(tsx)            \
  X(typescript)     \
  X(xml)

#define DECLARE(name) const TSLanguage *tree_sitter_##name(void);
GRAMMARS(DECLARE)

#define ENTRY(name) {#name, tree_sitter_##name},
static const struct {
  const char *name;
  const TSLanguage *(*language)(void);
} grammars[] = {GRAMMARS(ENTRY)};

#define BYTES_PER_CHAR 2

static void throw_illegal_argument(JNIEnv *env, const char *message) {
  jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
  if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static void throw_out_of_memory(JNIEnv *env, const char *message) {
  jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
  if (type != NULL) (*env)->ThrowNew(env, type, message);
}

JNIEXPORT jlong JNICALL
Java_io_jeph_pipkin_syntax_TreeSitter_language(JNIEnv *env, jclass type, jstring name) {
  const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
  if (chars == NULL) return 0;
  const TSLanguage *language = NULL;
  for (size_t i = 0; i < sizeof(grammars) / sizeof(grammars[0]); i++) {
    if (strcmp(grammars[i].name, chars) == 0) {
      language = grammars[i].language();
      break;
    }
  }
  (*env)->ReleaseStringUTFChars(env, name, chars);
  return (jlong)(intptr_t)language;
}

JNIEXPORT jlong JNICALL
Java_io_jeph_pipkin_syntax_TreeSitter_parserNew(JNIEnv *env, jclass type, jlong language) {
  TSParser *parser = ts_parser_new();
  if (!ts_parser_set_language(parser, (const TSLanguage *)(intptr_t)language)) {
    ts_parser_delete(parser);
    throw_illegal_argument(env, "grammar ABI version is not supported by this tree-sitter");
    return 0;
  }
  return (jlong)(intptr_t)parser;
}

JNIEXPORT void JNICALL
Java_io_jeph_pipkin_syntax_TreeSitter_parserDelete(JNIEnv *env, jclass type, jlong parser) {
  ts_parser_delete((TSParser *)(intptr_t)parser);
}

JNIEXPORT jlong JNICALL Java_io_jeph_pipkin_syntax_TreeSitter_parse(
    JNIEnv *env, jclass type, jlong parser, jlong old_tree, jstring text) {
  jsize length = (*env)->GetStringLength(env, text);
  const jchar *chars = (*env)->GetStringChars(env, text, NULL);
  if (chars == NULL) return 0;
  // Android's ABIs are all little-endian, so Java's chars are UTF-16LE in memory.
  TSTree *tree = ts_parser_parse_string_encoding(
      (TSParser *)(intptr_t)parser, (const TSTree *)(intptr_t)old_tree, (const char *)chars,
      (uint32_t)length * BYTES_PER_CHAR, TSInputEncodingUTF16LE);
  (*env)->ReleaseStringChars(env, text, chars);
  return (jlong)(intptr_t)tree;
}

JNIEXPORT void JNICALL Java_io_jeph_pipkin_syntax_TreeSitter_treeEdit(
    JNIEnv *env, jclass type, jlong tree, jintArray edit) {
  jint e[9];
  (*env)->GetIntArrayRegion(env, edit, 0, 9, e);
  TSInputEdit input = {
      .start_byte = (uint32_t)e[0] * BYTES_PER_CHAR,
      .old_end_byte = (uint32_t)e[1] * BYTES_PER_CHAR,
      .new_end_byte = (uint32_t)e[2] * BYTES_PER_CHAR,
      .start_point = {(uint32_t)e[3], (uint32_t)e[4] * BYTES_PER_CHAR},
      .old_end_point = {(uint32_t)e[5], (uint32_t)e[6] * BYTES_PER_CHAR},
      .new_end_point = {(uint32_t)e[7], (uint32_t)e[8] * BYTES_PER_CHAR},
  };
  ts_tree_edit((TSTree *)(intptr_t)tree, &input);
}

JNIEXPORT void JNICALL
Java_io_jeph_pipkin_syntax_TreeSitter_treeDelete(JNIEnv *env, jclass type, jlong tree) {
  ts_tree_delete((TSTree *)(intptr_t)tree);
}

static const char *query_error_name(TSQueryError error) {
  switch (error) {
    case TSQueryErrorSyntax: return "syntax";
    case TSQueryErrorNodeType: return "node type";
    case TSQueryErrorField: return "field";
    case TSQueryErrorCapture: return "capture";
    case TSQueryErrorStructure: return "structure";
    case TSQueryErrorLanguage: return "language";
    default: return "unknown";
  }
}

JNIEXPORT jlong JNICALL Java_io_jeph_pipkin_syntax_TreeSitter_queryNew(
    JNIEnv *env, jclass type, jlong language, jstring source) {
  const char *chars = (*env)->GetStringUTFChars(env, source, NULL);
  if (chars == NULL) return 0;
  uint32_t error_offset = 0;
  TSQueryError error = TSQueryErrorNone;
  TSQuery *query = ts_query_new((const TSLanguage *)(intptr_t)language, chars,
                                (uint32_t)strlen(chars), &error_offset, &error);
  (*env)->ReleaseStringUTFChars(env, source, chars);
  if (query == NULL) {
    char message[96];
    snprintf(message, sizeof(message), "query %s error at byte %u", query_error_name(error),
             error_offset);
    throw_illegal_argument(env, message);
    return 0;
  }
  return (jlong)(intptr_t)query;
}

static jobjectArray strings(JNIEnv *env, const TSQuery *query, uint32_t count,
                            const char *(*value)(const TSQuery *, uint32_t, uint32_t *)) {
  jclass string_type = (*env)->FindClass(env, "java/lang/String");
  if (string_type == NULL) return NULL;
  jobjectArray result = (*env)->NewObjectArray(env, (jsize)count, string_type, NULL);
  for (uint32_t i = 0; result != NULL && i < count; i++) {
    uint32_t length = 0;
    const char *chars = value(query, i, &length);
    // NewStringUTF reads modified UTF-8, the same bytes as UTF-8 for the BMP text queries
    // hold, and needs the terminated copy that tree-sitter's length-delimited names lack.
    char *copy = malloc(length + 1);
    if (copy == NULL) {
      throw_out_of_memory(env, "no memory for a query string");
      return NULL;
    }
    memcpy(copy, chars, length);
    copy[length] = '\0';
    jstring string = (*env)->NewStringUTF(env, copy);
    free(copy);
    if (string == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, result, (jsize)i, string);
    (*env)->DeleteLocalRef(env, string);
  }
  return result;
}

JNIEXPORT jobjectArray JNICALL
Java_io_jeph_pipkin_syntax_TreeSitter_queryCaptureNames(JNIEnv *env, jclass type, jlong query) {
  const TSQuery *q = (const TSQuery *)(intptr_t)query;
  return strings(env, q, ts_query_capture_count(q), ts_query_capture_name_for_id);
}

JNIEXPORT jobjectArray JNICALL
Java_io_jeph_pipkin_syntax_TreeSitter_queryStrings(JNIEnv *env, jclass type, jlong query) {
  const TSQuery *q = (const TSQuery *)(intptr_t)query;
  return strings(env, q, ts_query_string_count(q), ts_query_string_value_for_id);
}

JNIEXPORT jint JNICALL
Java_io_jeph_pipkin_syntax_TreeSitter_queryPatternCount(JNIEnv *env, jclass type, jlong query) {
  return (jint)ts_query_pattern_count((const TSQuery *)(intptr_t)query);
}

// Pairs of (step type, value id) for one pattern's predicates, each ended by a Done step.
JNIEXPORT jintArray JNICALL Java_io_jeph_pipkin_syntax_TreeSitter_queryPredicates(
    JNIEnv *env, jclass type, jlong query, jint pattern) {
  uint32_t count = 0;
  const TSQueryPredicateStep *steps =
      ts_query_predicates_for_pattern((const TSQuery *)(intptr_t)query, (uint32_t)pattern, &count);
  jintArray result = (*env)->NewIntArray(env, (jsize)(count * 2));
  if (result == NULL) return NULL;
  jint *values = (*env)->GetIntArrayElements(env, result, NULL);
  if (values == NULL) return NULL;
  for (uint32_t i = 0; i < count; i++) {
    values[i * 2] = (jint)steps[i].type;
    values[i * 2 + 1] = (jint)steps[i].value_id;
  }
  (*env)->ReleaseIntArrayElements(env, result, values, 0);
  return result;
}

typedef struct {
  jint *values;
  size_t length;
  size_t capacity;
} IntBuffer;

static int push(IntBuffer *buffer, jint value) {
  if (buffer->length == buffer->capacity) {
    size_t capacity = buffer->capacity == 0 ? 1024 : buffer->capacity * 2;
    jint *values = realloc(buffer->values, capacity * sizeof(jint));
    if (values == NULL) return 0;
    buffer->values = values;
    buffer->capacity = capacity;
  }
  buffer->values[buffer->length++] = value;
  return 1;
}

// Every match of [query] over [tree] in one array: the pattern index and capture count, then for
// each capture its index and start and end char. Predicates are left to the caller.
JNIEXPORT jintArray JNICALL Java_io_jeph_pipkin_syntax_TreeSitter_matches(
    JNIEnv *env, jclass type, jlong tree, jlong query) {
  TSQueryCursor *cursor = ts_query_cursor_new();
  ts_query_cursor_exec(cursor, (const TSQuery *)(intptr_t)query,
                       ts_tree_root_node((const TSTree *)(intptr_t)tree));
  IntBuffer buffer = {0};
  TSQueryMatch match;
  int ok = 1;
  while (ok && ts_query_cursor_next_match(cursor, &match)) {
    ok = push(&buffer, match.pattern_index) && push(&buffer, match.capture_count);
    for (uint16_t i = 0; ok && i < match.capture_count; i++) {
      TSNode node = match.captures[i].node;
      ok = push(&buffer, (jint)match.captures[i].index) &&
           push(&buffer, (jint)(ts_node_start_byte(node) / BYTES_PER_CHAR)) &&
           push(&buffer, (jint)(ts_node_end_byte(node) / BYTES_PER_CHAR));
    }
  }
  ts_query_cursor_delete(cursor);
  jintArray result = NULL;
  if (ok) {
    result = (*env)->NewIntArray(env, (jsize)buffer.length);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, (jsize)buffer.length, buffer.values);
  } else {
    throw_out_of_memory(env, "no memory for query matches");
  }
  free(buffer.values);
  return result;
}
