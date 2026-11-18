// From sherpa-onnx v2.0.1 (Apache-2.0), sherpa-onnx/kotlin-api. The native library finds these classes,
// fields and methods by name, so keep them as they are.
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
