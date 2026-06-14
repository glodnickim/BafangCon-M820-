package com.test.bafangcon

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Ekran awaryjny pokazujący pełny stack trace, gdy aplikacja się wywali.
 * Działa w osobnym procesie (`:crash` w manifeście), więc przeżywa śmierć głównego procesu.
 * Tekst jest zaznaczalny — można go skopiować lub zrobić zrzut ekranu do diagnozy.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "(brak stack trace)"

        val textView = TextView(this).apply {
            text = "CRASH — wyślij ten tekst:\n\n$trace"
            setTextIsSelectable(true)
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(24, 48, 24, 24)
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#B00020"))
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scroll)
    }

    companion object {
        const val EXTRA_TRACE = "trace"
    }
}
