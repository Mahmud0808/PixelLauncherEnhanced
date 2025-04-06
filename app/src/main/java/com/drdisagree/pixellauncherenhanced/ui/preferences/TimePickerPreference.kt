package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.content.res.TypedArray
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/*
* Modified from https://github.com/etidoUP/Material-Time-picker-preference-
* Credits: etidoUP
*/

class TimePickerPreference : Preference {
    private var timeValue: String? = "00:00"

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        layoutResource = R.layout.custom_preference_time_picker
        if (attrs != null) {
            val a =
                context.theme.obtainStyledAttributes(
                    attrs,
                    R.styleable.MaterialTimePickerPreference,
                    0,
                    0
                )
            timeValue = try {
                a.getString(R.styleable.MaterialTimePickerPreference_presetValue)
            } catch (e: Exception) {
                "00:00"
            } finally {
                a.recycle()
            }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val timeTextView = holder.findViewById(R.id.time_stamp) as TextView
        timeTextView.text = timeValue

        setFirstAndLastItemMargin(holder)
    }

    override fun onClick() {
        super.onClick()

        // parse hour and minute from timeValue
        val hour = AtomicInteger(
            timeValue!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()[0].toInt()
        )
        val minute = AtomicInteger(
            timeValue!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()[1].toInt()
        )

        val timePicker =
            MaterialTimePicker.Builder().setTimeFormat(
                if (DateFormat.is24HourFormat(
                        context
                    )
                ) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
            ).setHour(hour.get()).setMinute(minute.get()).build()

        timePicker.addOnPositiveButtonClickListener { v: View? ->
            hour.set(timePicker.hour)
            minute.set(timePicker.minute)
            val selectedTime = String.format(
                Locale.getDefault(),
                "%02d:%02d",
                hour.get(),
                minute.get()
            )

            timeValue = selectedTime
            persistString(selectedTime)
            notifyChanged()
        }

        timePicker.show((context as AppCompatActivity).supportFragmentManager, "timePicker")
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getString(index)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        timeValue = getPersistedString(defaultValue as String?)
        persistString(timeValue)
    }

    fun getTimeValue(): String? {
        return this.timeValue
    }

    fun setTimeValue(timeValue: String?) {
        this.timeValue = timeValue
        persistString(timeValue)
        notifyChanged()
    }
}
