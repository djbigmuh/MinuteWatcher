package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.minutewatcher.core.resources.R

class PinWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Toast
            .makeText(
                context,
                context.getString(R.string.widget_added_success),
                Toast.LENGTH_SHORT,
            ).show()
    }
}
