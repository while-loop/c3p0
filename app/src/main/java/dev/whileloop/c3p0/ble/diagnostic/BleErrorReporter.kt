package dev.whileloop.c3p0.ble.diagnostic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleErrorReporter @Inject constructor() {
    private val _errors = MutableStateFlow<List<BleErrorMessage>>(emptyList())
    val errors: StateFlow<List<BleErrorMessage>> = _errors.asStateFlow()
    private val nextId = AtomicLong(1L)

    fun report(source: String, message: String, detail: String? = null) {
        val error = BleErrorMessage(
            id = nextId.getAndIncrement(),
            source = source,
            message = message,
            detail = detail
        )
        _errors.update { current -> (current + error).takeLast(MAX_ERRORS) }
    }

    fun dismiss(id: Long) {
        _errors.update { current -> current.filterNot { it.id == id } }
    }

    fun clear() {
        _errors.value = emptyList()
    }

    private companion object {
        private const val MAX_ERRORS = 20
    }
}

data class BleErrorMessage(
    val id: Long,
    val source: String,
    val message: String,
    val detail: String? = null
)
