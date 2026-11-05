package dev.filmroom.imprint

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
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
        fun dateTime(): LocalDateTime? = date?.let { LocalDateTime.of(it, time ?: LocalTime.MIDNIGHT) }
    }

    private class DraftScrollView(context: Context, val dateDraft: DateDraft) : ScrollView(context) {
        var interrupted: (() -> Unit)? = null
        var picker: AlertDialog? = null
        var pickerKind: String? = null
        fun dismissPicker() {
            val previous = picker
            picker = null
            pickerKind = null
            previous?.dismiss()
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus) interrupted?.invoke()
        }
        override fun onDetachedFromWindow() {
            interrupted?.invoke()
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
        putInt("imprint-placement", if (root.findViewWithTag<Switch>("imprint-placement").isChecked) 1 else 0)
        putInt("imprint-corner", root.findViewWithTag<Spinner>("imprint-corner").selectedItemPosition)
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

    fun show(context: Context, state: EditorState, metadata: PhotoMetadata, draft: Bundle? = null, onPreview: (ImprintSettings?) -> Unit = {}, onApply: (ImprintSettings) -> Unit): AlertDialog {
        val old = state.imprint
        // Keep the framework hierarchy intact: clock-mode actions live inside TimePicker, and
        // wrapping it in scroll views can mismeasure/clip those actions. Use native spinners when
        // a calendar/clock would not fit comfortably instead of making users pan the picker.
        val configuration = context.resources.configuration
        val compactPicker = configuration.screenWidthDp < 360 || configuration.screenHeightDp < 480 || configuration.fontScale > 1.35f
        val pickerTheme = if (compactPicker) dev.filmroom.R.style.ImprintCompactPickerTheme else 0
        fun t(key: ImprintText) = key.text(context)
        fun dp(n: Int) = (context.resources.displayMetrics.density * n + .5f).toInt()
        val original = metadata.imprintDateTime()
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
        label(t(ImprintText.DATE))
        val dateRow = LinearLayout(context).apply {
            tag = "imprint-dialog-date-row"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(dateRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val dateValue = action("imprint-date-value").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            // The adjacent icon keeps a full touch target; the date wraps on narrow screens.
            minWidth = 0; minimumWidth = 0
        }
        column.removeView(dateValue)
        dateRow.addView(dateValue, LinearLayout.LayoutParams(0, -2, 1f))
        val restore = ImageButton(context).apply {
            tag = "imprint-restore-capture"
            setImageResource(dev.filmroom.R.drawable.ic_restore_capture_date)
            imageTintList = android.content.res.ColorStateList.valueOf(dev.filmroom.ui.FilmroomPalette.muted)
            contentDescription = t(ImprintText.RESTORE_CAPTURE)
            tooltipText = contentDescription
            minimumWidth = dp(48); minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dateRow.addView(restore, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        val error = label(t(ImprintText.INVALID_DATE_TIME)).apply {
            tag = "imprint-date-error"; visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        var showDateError = draft?.getBoolean("imprint-date-error", false) ?: false
        val preview = label("").apply { tag = "imprint-content" }

        fun choice(key: ImprintText, labels: List<String>, index: Int, tagName: String): Spinner {
            label(t(key)).tag = "$tagName-label"
            return Spinner(context).apply {
                tag = tagName; contentDescription = t(key)
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection((draft?.getInt(tagName, index) ?: index).coerceIn(0, labels.lastIndex))
                minimumHeight = dp(48)
            }.also { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        // Placement is a 120-only option, separate from the date imprint master switch.
        // Retain the saved preference while hidden so changing format does not erase it.
        val placement = Switch(context).apply {
            tag = "imprint-placement"
            text = t(ImprintText.ON_EDGE)
            contentDescription = t(ImprintText.PLACEMENT) + " · " + t(ImprintText.ON_EDGE)
            textSize = 14f; minimumHeight = dp(48)
            setPadding(0, dp(10), 0, dp(3))
            isChecked = (draft?.getInt("imprint-placement", ImprintSettings.PLACEMENTS.indexOf(old.datePlacement))
                ?: ImprintSettings.PLACEMENTS.indexOf(old.datePlacement)) == 1
            visibility = if (state.filmBorder && state.effectiveMediumFormat != null) View.VISIBLE else View.GONE
        }.also { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val corner = choice(ImprintText.POSITION, listOf(ImprintText.BR, ImprintText.BL, ImprintText.TR, ImprintText.TL).map(::t), ImprintSettings.CORNERS.indexOf(old.corner), "imprint-corner")
        fun slider(key: ImprintText, start: Int, tagName: String): SeekBar {
            label(t(key)).tag = "$tagName-label"
            return SeekBar(context).apply {
                tag = tagName; contentDescription = t(key); max = 100
                progress = (draft?.getInt(tagName, start) ?: start).coerceIn(0, 100); minimumHeight = dp(48)
            }.also { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val size = slider(ImprintText.SIZE, ((old.size - .6) * 100).roundToInt(), "imprint-size")
        val sizeTip = label(t(ImprintText.EDGE_SIZE_FIXED)).apply {
            tag = "imprint-size-tip"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val glow = slider(ImprintText.GLOW, (old.glow * 100).roundToInt(), "imprint-glow")
        fun settings(): ImprintSettings = old.copy(
            dateStyle = styles[buttons.indexOfFirst { it.isChecked }.coerceAtLeast(0)],
            corner = ImprintSettings.CORNERS[corner.selectedItemPosition],
            size = .6 + size.progress / 100.0, glow = glow.progress / 100.0,
            datePlacement = if (placement.isChecked) "edge" else "photo",
            manualDateTime = if (values.manual) values.dateTime()?.let(ImprintSettings::formatDateTime) else null
        )
        var adjusting = false
        var lastPreviewKey: String? = null
        fun refresh() {
            // Edge dates have a fixed 120 placement. Keep the user's photo corner intact.
            val photoPositionEnabled = placement.visibility != View.VISIBLE || !placement.isChecked
            if (!adjusting) {
            size.isEnabled = photoPositionEnabled
            size.alpha = if (photoPositionEnabled) 1f else .4f
            size.tooltipText = if (photoPositionEnabled) null else t(ImprintText.EDGE_SIZE_FIXED)
            column.findViewWithTag<TextView>("imprint-size-label").apply {
                isEnabled = photoPositionEnabled
                alpha = if (photoPositionEnabled) 1f else .4f
            }
            sizeTip.visibility = if (photoPositionEnabled) View.GONE else View.VISIBLE
            corner.isEnabled = photoPositionEnabled
            corner.alpha = if (photoPositionEnabled) 1f else .4f
            column.findViewWithTag<TextView>("imprint-corner-label").apply {
                isEnabled = photoPositionEnabled
                alpha = if (photoPositionEnabled) 1f else .4f
            }
            }
            val dateTime = values.dateTime()
            dateValue.text = t(ImprintText.DATE) + " · " + (values.date?.toString() ?: t(ImprintText.CHOOSE_DATE))
            dateValue.contentDescription = dateValue.text
            error.visibility = if (showDateError && values.manual && dateTime == null) View.VISIBLE else View.GONE
            val selected = buttons.indexOfFirst { it.isChecked }.coerceAtLeast(0)
            val formatted = dateTime?.toLocalDate()?.let { ImprintContent.formatDate(it, styles[selected]) }
            preview.text = formatted ?: if (values.manual) t(ImprintText.INVALID_DATE_TIME) else t(ImprintText.MISSING)
            // Preview and output share the same date-only lettering.
            val sample = values.date ?: sampleDate
            sampleNote.text = if (dateTime == null) t(ImprintText.STYLE_SAMPLE) + " · " + sample else ""
            sampleNote.visibility = if (dateTime == null) View.VISIBLE else View.GONE
            // A preview is presentation-only, including when the date master switch is off.
            // Incomplete manual input must not silently preview the source date.
            onPreview(if (values.manual && dateTime == null) null else settings().copy(dateEnabled = true))
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
                            values.date = chosen; values.time = values.time ?: LocalTime.MIDNIGHT; values.manual = true; refresh()
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
            } else return
            scroll.picker = picker
            scroll.pickerKind = kind
            picker.setOnDismissListener {
                if (scroll.picker === picker) { scroll.picker = null; scroll.pickerKind = null }
            }
            savedState?.let(picker::onRestoreInstanceState)
            picker.show()
        }
        dateValue.setOnClickListener { openPicker("date") }
        restore.setOnClickListener {
            values.date = original?.toLocalDate(); values.time = original?.toLocalTime()
            values.manual = false; showDateError = false; refresh()
        }
        group.setOnCheckedChangeListener { _, _ -> refresh() }
        placement.setOnCheckedChangeListener { _, _ -> refresh() }
        val fade = ImprintAdjustmentFade(dialog)
        scroll.interrupted = { adjusting = false; fade.finishImmediately() }
        val sliderListener = object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { refresh() }
            override fun onStartTrackingTouch(bar: SeekBar?) { adjusting = true; bar?.let(fade::begin) }
            override fun onStopTrackingTouch(bar: SeekBar?) { adjusting = false; fade.end() }
        }
        size.setOnSeekBarChangeListener(sliderListener)
        glow.setOnSeekBarChangeListener(sliderListener)
        corner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refresh() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        dialog.setOnDismissListener { fade.finishImmediately(); scroll.dismissPicker(); onPreview(null) }
        refresh()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dateTime = values.dateTime()
                // Missing source date is valid; a manual override must contain a selected date.
                if (values.manual && dateTime == null) {
                    showDateError = true; refresh()
                    val missingControl = dateValue
                    missingControl.requestFocus()
                    scroll.smoothScrollTo(0, dateValue.top)
                    error.announceForAccessibility(error.text)
                    return@setOnClickListener
                }
                onApply(settings())
                dialog.dismiss()
            }
            scroll.post {
                if (dialog.isShowing) {
                    scroll.scrollTo(0, draft?.getInt("imprint-scroll", 0) ?: 0)
                    draft?.getString("imprint-picker")?.takeIf { it == "date" }?.let {
                        openPicker(it, draft.getBundle("imprint-picker-state"))
                    }
                }
            }
        }
        dialog.show()
        return dialog
    }
}
