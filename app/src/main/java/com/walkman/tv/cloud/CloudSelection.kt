package com.walkman.tv.cloud

import android.content.Context
import com.walkman.tv.data.model.SourceID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CloudSelection(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_selection", Context.MODE_PRIVATE)
    private val mutableSource = MutableStateFlow(
        SourceID.fromKey(prefs.getString("active_source", "wy"))?.takeIf {
            it == SourceID.WY || it == SourceID.TX
        } ?: SourceID.WY
    )
    val source = mutableSource.asStateFlow()
    fun select(source: SourceID) {
        require(source == SourceID.WY || source == SourceID.TX)
        prefs.edit().putString("active_source", source.key).apply()
        mutableSource.value = source
    }
}
