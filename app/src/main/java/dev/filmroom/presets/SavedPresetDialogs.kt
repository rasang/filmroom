package dev.filmroom.presets

import dev.filmroom.diagnostics.*
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.filmroom.EditorUiState
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import dev.filmroom.model.EditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog drafts are not edits. Only the explicit Apply button calls the editor's atomic update. */
internal class SavedPresetDialogs(
    private val activity: ComponentActivity,
    private val state: () -> EditorUiState,
    private val apply: (String, EditorState, EditorState) -> Boolean,
    private val showReadable: (AlertDialog) -> Unit,
    private val scanRecipe: () -> Unit = {},
) {
    private val store = SavedPresetStore(activity)
    private var dialog: AlertDialog? = null
    private var confirmation: AlertDialog? = null
    private var mode: String? = null
    private var source: String? = null
    private var baseline: EditorState? = null
    private var nameField: EditText? = null
    private var selection: String? = null
    private var working = false
    private var listJob: Job? = null
    private var actionMenu: PopupMenu? = null
    private var shareDialog: PresetQrShareDialog? = null
    private var returnToList = false
    private var nextDialog: (() -> Unit)? = null
    internal var timingObserver: ((String, Long, Boolean) -> Unit)? = null
    private inline fun <T> measured(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        android.os.Trace.beginSection("Recipe $stage")
        try { return block() } finally {
            android.os.Trace.endSection()
            timingObserver?.invoke(stage, System.nanoTime() - start, android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        }
    }
    private fun text(id: Int, vararg args: Any?) = L10n.text(id, *args)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
    private fun active(target: AlertDialog) = dialog === target && target.isShowing &&
        !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun canOpen(): Boolean = dialog == null && state().let { it.editable && !it.compositionEditing }

    fun save(draft: String = "", returnToRecipes: Boolean = false) {
        if (!canOpen()) return
        val current = state()
        source = current.photo!!.cacheKey
        baseline = current.parameters
        mode = "save"
        returnToList = returnToRecipes
        val input = EditText(activity).apply {
            tag = "saved-preset-name"
            hint = text(R.string.saved_preset_name)
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(draft)
            setSelection(length())
        }
        nameField = input
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(activity).apply { text = text(R.string.saved_preset_scope); setPadding(0, dp(12), 0, 0) })
        }
        val target = AlertDialog.Builder(activity).setTitle(text(R.string.saved_preset_save)).setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton(text(R.string.saved_preset_cancel), null)
            .setPositiveButton(text(R.string.saved_preset_save), null).create()
        track(target)
        target.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (working || !active(target)) return@setOnClickListener
            val name = try { SavedPresetStore.cleanName(input.text.toString()) }
            catch (error: SavedPresetStore.Failure) { RecipeLog(RecipeAction.SAVE).failed(error); input.error = problem(error); return@setOnClickListener }
            if (!unchanged()) { RecipeLog(RecipeAction.SAVE).end(Outcome.FAILED, RecipeReason.STALE); input.error = text(R.string.saved_preset_stale); return@setOnClickListener }
            val parameters = checkNotNull(baseline)
            working = true
            target.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            target.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            target.setCancelable(false); target.setCanceledOnTouchOutside(false)
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { store.save(name, parameters) }
                    if (active(target)) { Toast.makeText(activity, text(R.string.saved_preset_saved), Toast.LENGTH_SHORT).show(); target.dismiss() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (active(target)) input.error = problem(error) }
                finally { if (dialog === target) {
                    working = false
                    target.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    target.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    target.setCancelable(true); target.setCanceledOnTouchOutside(true)
                } }
            }
        }
    }

    fun open(selectedId: String? = null, sharedId: String? = null) = measured("dialog create") {
        if (!canOpen()) return@measured
        val current = state()
        source = current.photo!!.cacheKey
        baseline = current.parameters
        mode = "import"
        returnToList = false
        selection = selectedId
        val status = TextView(activity).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(24), dp(12), dp(24), dp(8))
            tag = "recipe-list-instructions"
            text = text(R.string.saved_preset_choose)
            visibility = if (activity.resources.configuration.screenHeightDp < 480) View.GONE else View.VISIBLE
        }
        val emptyStatus = TextView(activity).apply {
            tag = "recipe-list-empty"
            text = text(R.string.saved_preset_loading)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val list = ListView(activity).apply { tag = "saved-preset-list"; choiceMode = ListView.CHOICE_MODE_SINGLE; emptyView = emptyStatus }
        val qrMenu = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_recipe_scan)
            imageTintList = android.content.res.ColorStateList.valueOf(dev.filmroom.ui.FilmroomPalette.ink)
            contentDescription = text(R.string.preset_qr_import)
            tooltipText = contentDescription
            background = null
            minimumHeight = dp(48); minimumWidth = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12)); tag = "recipe-qr-scan"
        }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(12), dp(12), 0)
            addView(TextView(activity).apply { text = text(R.string.saved_preset_import); textSize = 20f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(qrMenu, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, -2))
            // Reserve list space while reading, so arrival of the rows does not move the actions.
            val minimumRowDp = (48 * activity.resources.configuration.fontScale + 24).toInt()
            addView(FrameLayout(activity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(emptyStatus, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, dp((activity.resources.configuration.screenHeightDp / 3).coerceAtLeast(minimumRowDp).coerceIn(96, 280))))
        }
        val target = AlertDialog.Builder(activity).setCustomTitle(title).setView(body)
            .setNegativeButton(text(R.string.saved_preset_cancel), null)
            .setNeutralButton(text(R.string.saved_preset_save_current), null)
            .setPositiveButton(text(R.string.saved_preset_apply), null).create()
        track(target)
        var items = emptyList<SavedPresetStore.Preset>()
        var restoreShare = sharedId
        fun selected() = items.firstOrNull { it.id == selection }
        fun buttons() {
            // Scanning and saving do not need the list read to finish.
            qrMenu.isEnabled = !working
            target.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !working && selected() != null
            target.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !working
        }
        fun share(item: SavedPresetStore.Preset) {
            if (working || !active(target) || confirmation != null || shareDialog != null) return
            actionMenu?.dismiss()
            shareDialog = PresetQrShareDialog(activity, showReadable) { shareDialog = null }.also { it.show(item) }
        }
        lateinit var reload: () -> Unit
        fun delete(item: SavedPresetStore.Preset) {
            if (working || !active(target) || confirmation != null || shareDialog != null) return
            val confirm = AlertDialog.Builder(activity).setTitle(text(R.string.saved_preset_delete))
                .setMessage(text(R.string.saved_preset_delete_question, item.name))
                .setNegativeButton(text(R.string.saved_preset_cancel), null)
                .setPositiveButton(text(R.string.saved_preset_delete), null).create()
            confirmation = confirm
            confirm.setOnDismissListener { if (confirmation === confirm) confirmation = null }
            showReadable(confirm)
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!active(target) || confirmation !== confirm || !confirm.isShowing || working) return@setOnClickListener
                working = true; buttons(); confirm.dismiss()
                target.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                target.setCancelable(false); target.setCanceledOnTouchOutside(false)
                activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.delete(item.id) }
                        if (active(target)) { if (selection == item.id) selection = null; reload() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (active(target)) {
                        working = false; status.visibility = View.VISIBLE; status.text = problem(error); buttons()
                        target.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        target.setCancelable(true); target.setCanceledOnTouchOutside(true)
                    } }
                }
            }
        }
        fun actions(anchor: View, item: SavedPresetStore.Preset): Boolean {
            if (working || !active(target) || confirmation != null || shareDialog != null) return false
            actionMenu?.dismiss()
            val popup = PopupMenu(activity, anchor)
            popup.menu.add(0, R.id.recipe_action_share, 0, text(R.string.preset_qr_share))
            popup.menu.add(0, R.id.recipe_action_delete, 1, text(R.string.saved_preset_delete))
            popup.setOnMenuItemClickListener { action ->
                if (!active(target) || working) return@setOnMenuItemClickListener false
                when (action.itemId) {
                    R.id.recipe_action_share -> share(item)
                    R.id.recipe_action_delete -> delete(item)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            popup.setOnDismissListener { if (actionMenu === popup) actionMenu = null }
            actionMenu = popup
            popup.show()
            return true
        }
        qrMenu.setOnClickListener {
            if (working || !active(target) || shareDialog != null || confirmation != null) return@setOnClickListener
            target.dismiss()
            scanRecipe()
        }
        reload = {
            listJob?.cancel()
            listJob = activity.lifecycleScope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) { measured("store read and names") {
                        val presets = store.list()
                        presets to presets.map { it.name }
                    } }
                    if (!active(target)) return@launch
                    measured("list bind") {
                        items = loaded.first
                        if (items.none { it.id == selection }) selection = null
                        list.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_single_choice, loaded.second) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                                (super.getView(position, convertView, parent) as TextView).apply {
                                    setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
                                    minimumHeight = dp(48); setPadding(dp(20), dp(12), dp(20), dp(12))
                                    setHorizontallyScrolling(false)
                                    layoutParams = AbsListView.LayoutParams(-1, -2)
                                    val item = items[position]
                                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                                            super.onInitializeAccessibilityNodeInfo(host, info)
                                            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.recipe_action_share, text(R.string.preset_qr_share)))
                                            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.recipe_action_delete, text(R.string.saved_preset_delete)))
                                        }
                                        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                                            R.id.recipe_action_share -> { share(item); true }
                                            R.id.recipe_action_delete -> { delete(item); true }
                                            else -> super.performAccessibilityAction(host, action, args)
                                        }
                                    }
                                }
                        }
                        if (selection != null) list.setItemChecked(items.indexOfFirst { it.id == selection }, true)
                        // The header's final instructions are present from the first frame.
                        // Loading and empty-state text stay inside the reserved list viewport,
                        // preventing a second dialog resize when one loading line becomes two.
                        status.text = text(R.string.saved_preset_choose)
                        status.visibility = if (activity.resources.configuration.screenHeightDp < 480) View.GONE else View.VISIBLE
                        emptyStatus.text = text(R.string.saved_preset_empty)
                    }
                    restoreShare?.let { id -> restoreShare = null; items.firstOrNull { it.id == id }?.let(::share) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (active(target)) { items = emptyList(); emptyStatus.text = problem(error); list.adapter = null } }
                finally { if (dialog === target) {
                    working = false; buttons()
                    target.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    target.setCancelable(true); target.setCanceledOnTouchOutside(true)
                } }
            }
        }
        list.setOnItemClickListener { _, _, position, _ -> if (!working) { selection = items.getOrNull(position)?.id; buttons() } }
        list.setOnItemLongClickListener { _, row, position, _ -> items.getOrNull(position)?.let { actions(row, it) } ?: false }
        list.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_F10 && event.isShiftPressed)) {
                val index = list.selectedItemPosition.takeIf { it >= 0 } ?: list.checkedItemPosition
                val anchor = list.getChildAt(index - list.firstVisiblePosition)
                items.getOrNull(index)?.let { if (anchor != null) actions(anchor, it) else false } ?: false
            } else false
        }
        target.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (working || !active(target)) return@setOnClickListener
            if (!unchanged()) { status.visibility = View.VISIBLE; status.text = text(R.string.saved_preset_stale); return@setOnClickListener }
            nextDialog = { save(returnToRecipes = true) }
            target.dismiss()
        }
        target.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (working || !active(target)) return@setOnClickListener
            val item = selected() ?: return@setOnClickListener
            if (!unchanged()) { RecipeLog(RecipeAction.APPLY, correlation = RecipeCorrelation.forApply(item.id)).end(Outcome.FAILED, RecipeReason.STALE); status.visibility = View.VISIBLE; status.text = text(R.string.saved_preset_stale); return@setOnClickListener }
            working = true; buttons()
            listJob = activity.lifecycleScope.launch {
                val log = RecipeLog(RecipeAction.APPLY, correlation = RecipeCorrelation.forApply(item.id))
                try {
                    log.at(RecipeStep.PARAMETERS)
                    val parameters = withContext(Dispatchers.IO) { measured("apply parse and stocks") { item.parameters().also(::validateStocks) } }
                    if (!active(target)) { log.end(Outcome.CANCELLED, RecipeReason.INACTIVE); return@launch }
                    log.at(RecipeStep.COMMIT)
                    if (unchanged() && apply(checkNotNull(source), checkNotNull(baseline), parameters)) { log.end(Outcome.SUCCESS); target.dismiss() }
                    else { log.end(Outcome.FAILED, RecipeReason.STALE); status.visibility = View.VISIBLE; status.text = text(R.string.saved_preset_stale) }
                } catch (cancelled: CancellationException) { log.failed(cancelled); throw cancelled }
                catch (error: Exception) { log.failed(error); if (active(target)) { status.visibility = View.VISIBLE; status.text = problem(error) } }
                finally { if (dialog === target) { working = false; buttons() } }
            }
        }
        buttons()
        // Defer cold I/O until the next UI frame rather than running it in the opening call.
        // Do not depend on onDraw: an obscured/paused window may never draw.
        list.postOnAnimation { if (active(target)) reload() }

    }

    private fun unchanged() = state().let { it.editable && !it.compositionEditing && it.photo?.cacheKey == source && it.parameters == baseline }
    private fun validateStocks(parameters: EditorState) {
        val rows = org.json.JSONObject(activity.assets.open("engine-data/profile_catalog.json").bufferedReader().use { it.readText() }).getJSONArray("profiles")
        if (!dev.filmroom.model.StockSelectionValidation.isSupported(parameters, rows))
            throw SavedPresetStore.Failure(SavedPresetStore.Problem.INCOMPATIBLE)
    }
    private fun track(target: AlertDialog) {
        dialog = target
        working = false
        target.setOnDismissListener {
            if (dialog === target) {
                val next = nextDialog
                nextDialog = null
                val backToList = returnToList && unchanged() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !activity.isFinishing && !activity.isDestroyed
                returnToList = false
                listJob?.cancel(); listJob = null
                actionMenu?.dismiss(); actionMenu = null
                shareDialog?.close(); shareDialog = null
                confirmation?.dismiss(); confirmation = null; dialog = null; mode = null; nameField = null; working = false
                if (!activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    if (next != null) next() else if (backToList) open()
                }
            }
        }
        showReadable(target)
    }
    fun capture(): Bundle? {
        if (dialog?.isShowing != true || working) return null
        return Bundle().apply {
            putString("mode", mode); putString("source", source); putString("baseline", baseline?.let { SavedPresetStore.snapshot(it) })
            putString("name", nameField?.text?.toString()); putString("selection", selection)
            putBoolean("returnToList", returnToList); putString("shareId", shareDialog?.presetId)
        }
    }
    fun restore(saved: Bundle) {
        val expected = runCatching { EditorState.fromJson(saved.getString("baseline") ?: return) }.getOrNull() ?: return
        if (state().photo?.cacheKey != saved.getString("source") || state().parameters != expected) return
        when (saved.getString("mode")) { "save" -> save(saved.getString("name").orEmpty(), saved.getBoolean("returnToList")); "import" -> open(saved.getString("selection"), saved.getString("shareId")) }
    }
    fun close() { nextDialog = null; returnToList = false; listJob?.cancel(); actionMenu?.dismiss(); shareDialog?.close(); shareDialog = null; confirmation?.dismiss(); confirmation = null; dialog?.dismiss(); dialog = null }
    private fun problem(error: Exception) = text(when ((error as? SavedPresetStore.Failure)?.problem) {
        SavedPresetStore.Problem.INVALID_NAME -> R.string.saved_preset_invalid_name
        SavedPresetStore.Problem.DUPLICATE_NAME -> R.string.saved_preset_duplicate_name
        SavedPresetStore.Problem.FULL -> R.string.saved_preset_full
        SavedPresetStore.Problem.INCOMPATIBLE -> R.string.saved_preset_incompatible
        SavedPresetStore.Problem.UNREADABLE -> R.string.saved_preset_unreadable
        else -> R.string.saved_preset_write_failed
    })
}
