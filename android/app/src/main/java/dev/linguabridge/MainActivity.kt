package dev.linguabridge

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// The inspector, including its permission declaration, exists only in the debug source set.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setText(R.string.release_placeholder)
            setPadding(32, 64, 32, 32)
        })
    }
}
