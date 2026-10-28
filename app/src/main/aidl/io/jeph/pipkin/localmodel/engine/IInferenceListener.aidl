package io.jeph.pipkin.localmodel.engine;

/** Events from the :inference process, as JSON: {"type":"progress","value":0.4}, {"type":"ready"}, {"type":"failed","code","message"}. */
oneway interface IInferenceListener {
  void onEvent(String eventJson);
}
