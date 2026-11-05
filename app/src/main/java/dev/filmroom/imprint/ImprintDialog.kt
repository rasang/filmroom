package dev.filmroom.imprint

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.model.EditorState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.roundToInt

/** Draft-only date editor. No settings are changed until a valid Apply. */
internal object ImprintDialog {
    private val styles = listOf("contax_t2", "yashica_t4", "canon_data_back", "canon_luna")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val sampleDate = LocalDate.of(2000, 1, 1)

    private data class DateDraft(var date: LocalDate?, var time: LocalTime?, var manual: Boolean) {
        fun dateTime(): LocalDateTime? = date?.let { date -> time?.let { LocalDateTime.of(date, it) } }
    }

    private class DraftScrollView(context: Context, val dateDraft: DateDraft) : ScrollView(context) {
        var picker: AlertDialog? = null
        var pickerKind: String? = null
        fun dismissPicker() {
            val previous = picker
            picker = null
            pickerKind = null
            previous?.dismiss()
        }
        override fun onDetachedFromWindow() {
            // Closing the editor (including a source change) also closes its pending native picker.
            dismissPicker()
            super.onDetachedFromWindow()
        }
    }

    private fun parseTime(value: String?): LocalTime? = value?.let {
        // Reuse the strict presentation contract, including mandatory seconds and valid ranges.
        ImprintSettings.parseDateTime("2000-01-01 $it")?.toLocalTime()
    }

    private fun restoreDateDraft(draft: Bundle?, initial: LocalDateTime?, manual: Boolean): DateDraft {
        if (draft?.containsKey("imprint-date") == true || draft?.containsKey("imprint-time") == true) {
            return DateDraft(
                draft.getString("imprint-date")?.let(ImprintSettings::parseDate),
                parseTime(draft.getString("imprint-time")),
                draft.getBoolean("imprint-manual", manual)
            )
        }
        if (draft?.containsKey("imprint-datetime") == true) {
            val raw = draft.getString("imprint-datetime").orEmpty().take(64)
            val parsed = ImprintSettings.parseDateTime(raw)
            if (parsed != null) return DateDraft(parsed.toLocalDate(), parsed.toLocalTime(), draft.getBoolean("imprint-manual", manual))
            // Migrate an older typed draft without replacing an invalid/missing part with source time.
            // Any intact, strictly valid part survives; Apply still requires both manual parts.
            val date = ImprintSettings.parseDate(raw.substringBefore(' '))
            val time = parseTime(if (' ' in raw) raw.substringAfter(' ') else raw)
            return DateDraft(date, time, draft.getBoolean("imprint-manual", manual) || raw.isNotEmpty())
        }
        return DateDraft(initial?.toLocalDate(), initial?.toLocalTime(), manual)
    }

    fun capture(dialog: AlertDialog): Bundle = Bundle().apply {
        val root = checkNotNull(dialog.window).decorView
        val group = root.findViewWithTag<RadioGroup>("imprint-style")
        putInt("imprint-style", (0 until group.childCount).firstOrNull { group.getChildAt(it).id == group.checkedRadioButtonId } ?: 0)
        for (tag in listOf("imprint-placement", "imprint-corner")) putInt(tag, root.findViewWithTag<Spinner>(tag).selectedItemPosition)
        for (tag in listOf("imprint-size", "imprint-glow")) putInt(tag, root.findViewWithTag<SeekBar>(tag).progress)
        val scroll = root.findViewWithTag<DraftScrollView>("imprint-scroll")
        val values = scroll.dateDraft
        putString("imprint-date", values.date?.toString())
        putString("imprint-time", values.time?.format(timeFormat))
        // Compatibility for older saved-state consumers; a partial selection is never a full value.
        putString("imprint-datetime", values.dateTime()?.let(ImprintSettings::formatDateTime).orEmpty())
        putBoolean("imprint-manual", values.manual)
        putInt("imprint-scroll", scroll.scrollY)
        putBoolean("imprint-date-error", root.findViewWithTag<TextView>("imprint-date-error").visibility == View.VISIBLE)
        scroll.picker?.takeIf { it.isShowing }?.let {
            putString("imprint-picker", scroll.pickerKind)
            putBundle("imprint-picker-state", it.onSaveInstanceState())
        }
    }

    fun show(context: Context, state: EditorState, metadata: PhotoMetadata, draft: Bundle? = null, onApply: (ImprintSettings) -> Unit): AlertDialog {
        val old = state.imprint
        // Keep the framework hierarchy intact: clock-mode actions live inside TimePicker, and
        // wrapping it in scroll views can mismeasure/clip those actions. Use native spinners when
        // a calendar/clock would not fit comfortably instead of making users pan the picker.
        val configuration = context.resources.configuration
        val compactPicker = configuration.screenWidthDp < 360 || configuration.screenHeightDp < 480 || configuration.fontScale > 1.35f
        val pickerTheme = if (compactPicker) dev.filmroom.R.style.ImprintCompactPickerTheme else 0
        fun t(key: ImprintText) = key.text(context)
        fun dp(n: Int) = (context.resources.displayMetrics.density * n + .5f).toInt()
        val original = ImprintContent.originalCaptureDateTime(metadata)
        val values = restoreDateDraft(draft, ImprintContent.displayDateTime(metadata, old), old.manualDateTime != null)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        val scroll = DraftScrollView(context, values).apply { tag = "imprint-scroll"; addView(column) }
        fun label(text: String) = TextView(context).apply {
            this.text = text; textSize = 14f
            setTextColor(dev.filmroom.ui.FilmroomPalette.muted)
            setPadding(0, dp(10), 0, dp(3))
        }.also { column.addView(it) }
        fun action(tagName: String) = Button(context).apply {
            tag = tagName; isAllCaps = false; textSize = 14f
            minHeight = dp(48); minimumHeight = dp(48)
            maxLines = Int.MAX_VALUE; ellipsize = null; setHorizontallyScrolling(false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }.also { column.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }

        label(t(ImprintText.DATE_STYLE))
        val sampleNote = label("").apply { tag = "imprint-style-sample" }
        // Radio choices are always visible; thumbnails use the same bounded glyph renderer as export.
        val group = RadioGroup(context).apply { tag = "imprint-style" }
        column.addView(group)
        val names = listOf(ImprintText.SEGMENT, ImprintText.DOT, ImprintText.STROKE, ImprintText.KANJI)
        val buttons = names.map { key -> RadioButton(context).apply {
            id = View.generateViewId(); text = t(key); textSize = 14f; minHeight = dp(48)
            maxLines = Int.MAX_VALUE; ellipsize = null; setHorizontallyScrolling(false)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            compoundDrawablePadding = dp(8)
            group.addView(this, RadioGroup.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        } }
        val initialStyle = (draft?.getInt("imprint-style", styles.indexOf(old.selectedDateStyle)) ?: styles.indexOf(old.selectedDateStyle)).coerceIn(styles.indices)
        group.check(buttons[initialStyle].id)
        label(t(ImprintText.DATE_TIME))
        val dateValue = action("imprint-date-value").apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val timeValue = action("imprint-time-value").apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val error = label(t(ImprintText.INVALID_DATE_TIME)).apply {
            tag = "imprint-date-error"; visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        var showDateError = draft?.getBoolean("imprint-date-error", false) ?: false
        val source = label("").apply { tag = "imprint-source" }
        val restore = action("imprint-restore-capture").apply { text = t(ImprintText.RESTORE_CAPTURE) }
        val preview = label("").apply { tag = "imprint-content" }
        label(t(ImprintText.DATE_NOTE))
        label(t(ImprintText.TIME_PRECISION_NOTE)).apply { tag = "imprint-time-note" }

        fun choice(key: ImprintText, labels: List<String>, index: Int, tagName: String): Spinner {
            label(t(key))
            return Spinner(context).apply {
                tag = tagName; contentDescription = t(key)
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection((draft?.getInt(tagName, index) ?: index).coerceIn(0, labels.lastIndex))
                minimumHeight = dp(48)
            }.also { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val placement = choice(ImprintText.PLACEMENT, listOf(t(ImprintText.IN_PHOTO), t(ImprintText.ON_EDGE)), ImprintSettings.PLACEMENTS.indexOf(old.datePlacement), "imprint-placement")
        val corner = choice(ImprintText.POSITION, listOf(ImprintText.BR, ImprintText.BL, ImprintText.TR, ImprintText.TL).map(::t), ImprintSettings.CORNERS.indexOf(old.corner), "imprint-corner")
        fun slider(key: ImprintText, start: Int, tagName: String): SeekBar {
            label(t(key))
            return SeekBar(context).apply {
                tag = tagName; contentDescription = t(key); max = 100
                progress = (draft?.getInt(tagName, start) ?: start).coerceIn(0, 100); minimumHeight = dp(48)
            }.also { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val size = slider(ImprintText.SIZE, ((old.size - .6) * 100).roundToInt(), "imprint-size")
        val glow = slider(ImprintText.GLOW, (old.glow * 100).roundToInt(), "imprint-glow")
        label(t(ImprintText.SHARED_APPEARANCE))
        var lastPreviewKey: String? = null
        fun refresh() {
            val dateTime = values.dateTime()
            dateValue.text = t(ImprintText.DATE) + " · " + (values.date?.toString() ?: t(ImprintText.CHOOSE_DATE))
            timeValue.text = t(ImprintText.TIME) + " · " + (values.time?.format(timeFormat) ?: t(ImprintText.CHOOSE_TIME))
            dateValue.contentDescription = dateValue.text
            timeValue.contentDescription = timeValue.text
            error.visibility = if (showDateError && values.manual && dateTime == null) View.VISIBLE else View.GONE
            source.isSelected = values.manual
            source.text = if (values.manual) t(ImprintText.MANUAL) else if (original == null) t(ImprintText.MISSING) else t(ImprintText.ORIGINAL)
            val selected = buttons.indexOfFirst { it.isChecked }.coerceAtLeast(0)
            val formatted = dateTime?.toLocalDate()?.let { ImprintContent.formatDate(it, styles[selected]) }
            preview.text = listOfNotNull(formatted ?: if (values.manual) t(ImprintText.INVALID_DATE_TIME) else t(ImprintText.MISSING),
                if (placement.selectedItemPosition == 1 && (!state.filmBorder || state.effectiveMediumFormat == null)) t(ImprintText.EDGE_DATE_INACTIVE) else null).joinToString("\n")
            // A selected date can preview its glyphs; it is never saved/printed without its time.
            val sample = values.date ?: sampleDate
            sampleNote.text = if (dateTime == null) t(ImprintText.STYLE_SAMPLE) + " · " + sample else ""
            sampleNote.visibility = if (dateTime == null) View.VISIBLE else View.GONE
            val key = "$sample|${glow.progress}"
            if (lastPreviewKey == key) return
            lastPreviewKey = key
            buttons.forEachIndexed { i, button ->
                val text = ImprintContent.formatDate(sample, styles[i])
                val drawable = text?.let {
                    // Fixed 20px lettering bounds all four previews independently of photo dimensions.
                    val raster = ImprintRaster.create(it, styles[i], 20.0, glow.progress / 100.0)
                    val bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
                    val row = IntArray(raster.width)
                    for (y in 0 until raster.height) {
                        raster.writeArgbRow(y, row)
                        bitmap.setPixels(row, 0, raster.width, 0, y, raster.width, 1)
                    }
                    android.graphics.drawable.BitmapDrawable(context.resources, bitmap).apply {
                        val width = dp((context.resources.configuration.screenWidthDp - 100).coerceIn(80, 210)).coerceAtMost(dp(raster.width))
                        setBounds(0, 0, width, (width.toDouble() * raster.height / raster.width).roundToInt())
                    }
                }
                button.setCompoundDrawables(null, null, null, drawable)
                button.contentDescription = t(names[i]) + ": " + (text ?: t(ImprintText.MISSING))
            }
        }
        val dialog = AlertDialog.Builder(context).setTitle(t(ImprintText.DATE_DIALOG)).setView(scroll)
            .setNegativeButton(t(ImprintText.CANCEL), null).setPositiveButton(t(ImprintText.APPLY), null).create()
        fun openPicker(kind: String, savedState: Bundle? = null) {
            if (!dialog.isShowing || scroll.picker?.isShowing == true) return
            val picker: AlertDialog = if (kind == "date") {
                // These fixed seeds position an unselected picker, never replace missing metadata.
                val seed = values.date ?: sampleDate
                DatePickerDialog(context, pickerTheme, { _, year, month, day ->
                    if (dialog.isShowing) {
                        val chosen = runCatching { LocalDate.of(year, month + 1, day) }.getOrNull()
                        if (chosen != null && chosen.year in 1..9999) {
                            values.date = chosen; values.manual = true; refresh()
                        }
                    }
                }, seed.year, seed.monthValue - 1, seed.dayOfMonth).apply {
                    // The presentation model accepts 0001–9999, not the default 1900–2100 picker range.
                    datePicker.minDate = GregorianCalendar().apply { clear(); set(1, 0, 1) }.timeInMillis
                    datePicker.maxDate = GregorianCalendar().apply { clear(); set(9999, 11, 31) }.timeInMillis
                    datePicker.updateDate(seed.year, seed.monthValue - 1, seed.dayOfMonth)
                    // DatePicker's native header starts from the process locale, unlike its
                    // context-localized month grid. Synchronize only this widget to the app locale.
                    datePicker.dispatchConfigurationChanged(android.content.res.Configuration(context.resources.configuration))
                    setTitle(t(ImprintText.CHOOSE_DATE))
                    setButton(AlertDialog.BUTTON_POSITIVE, t(ImprintText.SELECT), this)
                    setButton(AlertDialog.BUTTON_NEGATIVE, t(ImprintText.CANCEL), this)
                }
            } else {
                val seed = values.time ?: LocalTime.MIDNIGHT
                TimePickerDialog(context, pickerTheme, { _, hour, minute ->
                    if (dialog.isShowing) {
                        // The native picker edits minutes. Keep existing seconds; an explicit first
                        // selection creates :00 seconds, as explained beside the date/time controls.
                        values.time = LocalTime.of(hour, minute, values.time?.second ?: 0)
                        values.manual = true; refresh()
                    }
                }, seed.hour, seed.minute, DateFormat.is24HourFormat(context)).apply {
                    setTitle(t(ImprintText.CHOOSE_TIME))
                    setButton(AlertDialog.BUTTON_POSITIVE, t(ImprintText.SELECT), this)
                    setButton(AlertDialog.BUTTON_NEGATIVE, t(ImprintText.CANCEL), this)
                }
            }
            scroll.picker = picker
            scroll.pickerKind = kind
            picker.setOnDismissListener {
                if (scroll.picker === picker) { scroll.picker = null; scroll.pickerKind = null }
            }
            savedState?.let(picker::onRestoreInstanceState)
            picker.show()
        }
        dateValue.setOnClickListener { openPicker("date") }
        timeValue.setOnClickListener { openPicker("time") }
        restore.setOnClickListener {
            values.date = original?.toLocalDate(); values.time = original?.toLocalTime()
            values.manual = false; showDateError = false; refresh()
        }
        group.setOnCheckedChangeListener { _, _ -> refresh() }
        placement.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        glow.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { refresh() }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        dialog.setOnDismissListener { scroll.dismissPicker() }
        refresh()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dateTime = values.dateTime()
                // Missing original is valid, but a manual override must include both selected parts.
                if (values.manual && dateTime == null) {
                    showDateError = true; refresh()
                    val missingControl = if (values.date == null) dateValue else timeValue
                    missingControl.requestFocus()
                    scroll.smoothScrollTo(0, dateValue.top)
                    error.announceForAccessibility(error.text)
                    return@setOnClickListener
                }
                onApply(old.copy(
                    dateStyle = styles[buttons.indexOfFirst { it.isChecked }.coerceAtLeast(0)],
                    corner = ImprintSettings.CORNERS[corner.selectedItemPosition],
                    size = .6 + size.progress / 100.0, glow = glow.progress / 100.0,
                    datePlacement = ImprintSettings.PLACEMENTS[placement.selectedItemPosition],
                    manualDateTime = if (values.manual) checkNotNull(dateTime).let(ImprintSettings::formatDateTime) else null
                ))
                dialog.dismiss()
            }
            scroll.post {
                if (dialog.isShowing) {
                    scroll.scrollTo(0, draft?.getInt("imprint-scroll", 0) ?: 0)
                    draft?.getString("imprint-picker")?.takeIf { it == "date" || it == "time" }?.let {
                        openPicker(it, draft.getBundle("imprint-picker-state"))
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }
}
