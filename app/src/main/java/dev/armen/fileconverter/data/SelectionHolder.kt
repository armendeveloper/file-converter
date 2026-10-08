package dev.armen.fileconverter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The files the user picked or shared, shown on the Convert screen. */
class SelectionHolder {
    private val _inputs = MutableStateFlow<List<InputInfo>>(emptyList())
    val inputs: StateFlow<List<InputInfo>> = _inputs.asStateFlow()

    fun set(inputs: List<InputInfo>) {
        _inputs.value = inputs
    }
}
