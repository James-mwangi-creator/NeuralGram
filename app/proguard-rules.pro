# NeuralGram - keep JNI methods from being stripped
-keep class com.neuralgram.app.MainActivity {
    native <methods>;
}
