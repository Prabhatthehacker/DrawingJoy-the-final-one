package com.mom.privatedrawing

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog

object ColorPickerDialog {

    fun show(
        context: Context,
        initialColor: Int,
        onUseColor: (Int) -> Unit,
        onAddToMyColors: (Int) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val wheel = view.findViewById<ColorWheelView>(R.id.colorWheel)
        val brightness = view.findViewById<SeekBar>(R.id.brightnessSlider)
        val preview = view.findViewById<android.view.View>(R.id.colorPreview)
        val hexInput = view.findViewById<EditText>(R.id.hexInput)
        val btnAdd = view.findViewById<android.widget.Button>(R.id.btnAddToMyColors)
        val btnUse = view.findViewById<android.widget.Button>(R.id.btnUseColor)

        fun updatePreview(color: Int) {
            preview.setBackgroundColor(color)
            hexInput.setText(String.format("#%06X", 0xFFFFFF and color))
        }

        wheel.setInitialColor(initialColor)
        val hsvInit = FloatArray(3)
        Color.colorToHSV(initialColor, hsvInit)
        brightness.progress = (hsvInit[2] * 100).toInt()
        updatePreview(initialColor)

        wheel.onColorPicked = { color -> updatePreview(color) }

        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                wheel.value = progress / 100f
                updatePreview(wheel.currentColor())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()

        hexInput.setOnEditorActionListener { _, _, _ ->
            val text = hexInput.text.toString().trim()
            try {
                val parsed = Color.parseColor(if (text.startsWith("#")) text else "#$text")
                updatePreview(parsed)
                wheel.setInitialColor(parsed)
            } catch (_: IllegalArgumentException) {
                // Ignore invalid hex while typing
            }
            false
        }

        btnAdd.setOnClickListener {
            onAddToMyColors(wheel.currentColor())
            dialog.dismiss()
        }

        btnUse.setOnClickListener {
            onUseColor(wheel.currentColor())
            dialog.dismiss()
        }

        dialog.show()
    }
}
