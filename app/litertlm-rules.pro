# LiteRT-LM (com.google.ai.edge.litertlm) ships no consumer rules. Its JNI library finds the callback methods
# (onMessage, onDone, onError), the config getters (getTopK, getEnableThinking, ...) and the classes it constructs
# (BenchmarkInfo, LiteRtLmJniException, InputData) by name, which R8 would otherwise rename or remove.
-keep class com.google.ai.edge.litertlm.** { *; }
