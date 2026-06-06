package com.example.offlinetranslate.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import uniffi.translatecore.coreVersion
import uniffi.translatecore.greeting
import uniffi.translatecore.sherpaVersion

interface DataRepository {
  val data: Flow<List<String>>
}

class DefaultDataRepository : DataRepository {
  // PoC: surface strings produced by the Rust core to prove the FFI bridge works.
  // sherpaVersion() additionally proves the heavy sherpa-onnx + onnxruntime stack loads.
  override val data: Flow<List<String>> = flow {
    emit(listOf(greeting("Android"), coreVersion(), sherpaVersion()))
  }
}
