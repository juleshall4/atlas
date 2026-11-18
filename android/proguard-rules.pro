# The native swipe engine calls these classes and constructors by name.
-keep class org.futo.ml.inference.** { *; }

# sherpa-onnx reads its settings and results through these classes by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
