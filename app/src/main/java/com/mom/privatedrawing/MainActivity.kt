package com.mom.privatedrawing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var leftPanel: View
    private lateinit var toolStrip: View
    private lateinit var topActions: View
    private lateinit var colorGrid: GridLayout
    private lateinit var customColorGrid: GridLayout
    private lateinit var brushSizeSlider: SeekBar
    private lateinit var colorsPanelContent: View
    private lateinit var textOptionsPanel: View
    private lateinit var textColorGrid: GridLayout
    private lateinit var textSizeSlider: SeekBar
    private lateinit var fontSpinner: Spinner

    private var deviceType: DeviceType = DeviceType.TABLET
    private var selectedTextColor: Int = Color.BLACK
    private var selectedFontFamily: Typeface = Typeface.DEFAULT
    private val fontOptions = listOf(
        "Default" to Typeface.DEFAULT,
        "Sans Serif" to Typeface.SANS_SERIF,
        "Serif" to Typeface.SERIF,
        "Monospace" to Typeface.MONOSPACE,
        "Bold" to Typeface.DEFAULT_BOLD
    )

    private var customColors = mutableListOf<Int>()
    private var selectedSwatch: View? = null

    private var isFullscreen = false

    // Rainbow order first (red \u2192 violet), then the neutrals: brown, black, white, gray.
    private val defaultPalette = listOf(
        Color.parseColor("#E53935"), // Red
        Color.parseColor("#FB8C00"), // Orange
        Color.parseColor("#FDD835"), // Yellow
        Color.parseColor("#43A047"), // Green
        Color.parseColor("#1E88E5"), // Blue
        Color.parseColor("#3949AB"), // Indigo
        Color.parseColor("#8E24AA"), // Purple
        Color.parseColor("#6D4C33"), // Brown
        Color.BLACK,
        Color.WHITE,
        Color.parseColor("#9E9E9E")  // Gray
    )

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, it)
            drawingView.addImage(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        leftPanel = findViewById(R.id.leftPanel)
        toolStrip = findViewById(R.id.toolStrip)
        topActions = findViewById(R.id.topActionsScroll)
        colorGrid = findViewById(R.id.colorGrid)
        customColorGrid = findViewById(R.id.customColorGrid)
        brushSizeSlider = findViewById(R.id.brushSizeSlider)
        colorsPanelContent = findViewById(R.id.colorsPanelContent)
        textOptionsPanel = findViewById(R.id.textOptionsPanel)
        textColorGrid = findViewById(R.id.textColorGrid)
        textSizeSlider = findViewById(R.id.textSizeSlider)
        fontSpinner = findViewById(R.id.fontSpinner)

        customColors = ColorStore.loadCustomColors(this)

        buildColorGrid()
        buildCustomColorGrid()
        buildToolStrip()
        wireBrushSizeControls()
        wireTopActions()
        setupTextOptionsPanel()

        drawingView.onHistoryChanged = { refreshUndoRedoState() }
        drawingView.onCanvasTapForText = { x, y -> showTextInputDialog(x, y) }

        val saved = DevicePrefs.getSavedDeviceType(this)
        if (saved == null) {
            showDeviceTypePicker(isFirstLaunch = true)
        } else {
            deviceType = saved
            applyDeviceSizing()
        }

        // Warn before leaving with unsaved work, instead of silently losing it.
        onBackPressedDispatcher.addCallback(this) {
            if (drawingView.canUndo()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Unsaved changes")
                    .setMessage("This drawing hasn't been saved yet. What would you like to do?")
                    .setNegativeButton("Discard") { _, _ -> finish() }
                    .setNeutralButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ -> saveDrawing(onSaved = { finish() }) }
                    .show()
            } else {
                finish()
            }
        }
    }

    // ---------------- Device type (phone / tablet) ----------------

    private fun showDeviceTypePicker(isFirstLaunch: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_device_type, null)
        val builder = AlertDialog.Builder(this).setView(view).setCancelable(!isFirstLaunch)
        val dialog = builder.create()

        view.findViewById<View>(R.id.optionPhone).setOnClickListener {
            deviceType = DeviceType.PHONE
            DevicePrefs.saveDeviceType(this, deviceType)
            applyDeviceSizing()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.optionTablet).setOnClickListener {
            deviceType = DeviceType.TABLET
            DevicePrefs.saveDeviceType(this, deviceType)
            applyDeviceSizing()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun applyDeviceSizing() {
        val density = resources.displayMetrics.density
        val isPhone = deviceType == DeviceType.PHONE

        // Left color panel width
        val panelWidthDp = if (isPhone) 128f else 150f
        leftPanel.layoutParams = leftPanel.layoutParams.apply {
            width = (panelWidthDp * density).toInt()
        }

        // Bottom tool strip buttons
        val toolButtonWidthDp = if (isPhone) 52f else 64f
        val labelSize = if (isPhone) 9.5f else 10.5f
        for (btn in toolButtons.values) {
            btn.layoutParams = LinearLayout.LayoutParams(
                (toolButtonWidthDp * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
            (btn.getChildAt(1) as? TextView)?.textSize = labelSize
        }

        // Top action pills (Record / More / Save / Share) — tighter on phones
        val pillHPaddingPx = ((if (isPhone) 10f else 14f) * density).toInt()
        val pillVPaddingPx = ((if (isPhone) 6f else 8f) * density).toInt()
        val pillTextSize = if (isPhone) 12f else 13f
        for (id in intArrayOf(R.id.btnMore, R.id.btnSave, R.id.btnShare)) {
            findViewById<TextView>(id)?.apply {
                setPadding(pillHPaddingPx, pillVPaddingPx, pillHPaddingPx, pillVPaddingPx)
                textSize = pillTextSize
            }
        }

        // Color swatches — rebuild so the new size in swatchLayoutParams() takes effect
        buildColorGrid()
        buildCustomColorGrid()
        setupTextOptionsPanel()

        leftPanel.requestLayout()
    }

    // ---------------- Colors ----------------

    // How many default-palette colors show directly in the side panel before
    // "See all colors" is needed. A tablet has room to just show everything;
    // a phone shows a generous 10 and hides the rest behind "See all colors".
    private fun quickPaletteCount(): Int =
        if (deviceType == DeviceType.PHONE) 10 else defaultPalette.size

    private fun buildColorGrid() {
        colorGrid.columnCount = 2
        colorGrid.removeAllViews()
        val visibleCount = quickPaletteCount()
        for (color in defaultPalette.take(visibleCount)) {
            colorGrid.addView(makeSwatch(color) { selectColor(color, it) })
        }
        colorGrid.addView(makeRainbowSwatch())

        if (visibleCount < defaultPalette.size) {
            val seeAll = TextView(this).apply {
                text = "See all colors \u2192"
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                setPadding(4, 10, 4, 4)
                setOnClickListener { showAllColorsDialog() }
            }
            val seeAllParams = GridLayout.LayoutParams()
            seeAllParams.columnSpec = GridLayout.spec(0, 2)
            seeAll.layoutParams = seeAllParams
            colorGrid.addView(seeAll)
        }
    }

    /** Full palette (all default colors + rainbow picker) in a bigger grid the panel can't fit. */
    private fun showAllColorsDialog() {
        val grid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
        }

        lateinit var dialog: AlertDialog

        for (color in defaultPalette) {
            grid.addView(makeSwatch(color) { view ->
                selectColor(color, view)
                dialog.dismiss()
            })
        }
        grid.addView(View(this).apply {
            layoutParams = swatchLayoutParams()
            val colors = intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
            )
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                gradientType = GradientDrawable.SWEEP_GRADIENT
                shape = GradientDrawable.OVAL
            }
            setOnClickListener {
                openColorPicker()
                dialog.dismiss()
            }
        })

        val scroll = android.widget.ScrollView(this).apply {
            setPadding(16, 16, 16, 0)
            addView(grid)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("All Colors")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun buildCustomColorGrid() {
        customColorGrid.columnCount = 2
        customColorGrid.removeAllViews()
        for (color in customColors) {
            customColorGrid.addView(makeSwatch(color) { selectColor(color, it) })
        }
        val addButton = TextView(this).apply {
            text = "+"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(3, ContextCompat.getColor(this@MainActivity, R.color.panel_border))
            }
            layoutParams = swatchLayoutParams()
            setOnClickListener { openColorPicker() }
        }
        customColorGrid.addView(addButton)
    }

    private fun swatchLayoutParams(): GridLayout.LayoutParams {
        // Kept big and uniform on every device on purpose — a small swatch doesn't feel
        // like picking a real color. Anything that doesn't fit in the side panel lives
        // behind the "See all colors" button instead of shrinking to squeeze in.
        val sizeDp = 40f
        val marginDp = 6f
        val density = resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        val marginPx = (marginDp * density).toInt()
        val params = GridLayout.LayoutParams()
        params.width = sizePx
        params.height = sizePx
        params.setMargins(marginPx, marginPx, marginPx, marginPx)
        return params
    }

    private fun makeSwatch(color: Int, onClick: (View) -> Unit): View {
        val swatch = View(this)
        swatch.layoutParams = swatchLayoutParams()
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(2, ContextCompat.getColor(this@MainActivity, R.color.panel_border))
        }
        swatch.setOnClickListener { onClick(swatch) }
        return swatch
    }

    private fun makeRainbowSwatch(): View {
        val swatch = View(this)
        swatch.layoutParams = swatchLayoutParams()
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        swatch.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            gradientType = GradientDrawable.SWEEP_GRADIENT
            shape = GradientDrawable.OVAL
        }
        swatch.setOnClickListener { openColorPicker() }
        return swatch
    }

    private fun selectColor(color: Int, view: View) {
        drawingView.currentColor = color
        selectedSwatch = view
        highlightSelected(view)
    }

    private fun highlightSelected(view: View) {
        for (grid in listOf(colorGrid, customColorGrid, textColorGrid)) {
            for (i in 0 until grid.childCount) {
                grid.getChildAt(i).scaleX = 1f
                grid.getChildAt(i).scaleY = 1f
            }
        }
        view.scaleX = 1.15f
        view.scaleY = 1.15f
    }

    private fun openColorPicker() {
        ColorPickerDialog.show(
            this,
            drawingView.currentColor,
            onUseColor = { color -> drawingView.currentColor = color },
            onAddToMyColors = { color ->
                customColors.add(0, color)
                if (customColors.size > 12) customColors = customColors.take(12).toMutableList()
                ColorStore.saveCustomColors(this, customColors)
                buildCustomColorGrid()
                drawingView.currentColor = color
            }
        )
    }

    // ---------------- Tools ----------------

    private data class ToolEntry(val tool: Tool, val label: String, val iconRes: Int)

    private val toolEntries = listOf(
        ToolEntry(Tool.PEN, "Pen", R.drawable.ic_tool_pen),
        ToolEntry(Tool.PENCIL, "Pencil", R.drawable.ic_tool_pencil),
        ToolEntry(Tool.MARKER, "Marker", R.drawable.ic_tool_marker),
        ToolEntry(Tool.HIGHLIGHTER, "Highlighter", R.drawable.ic_tool_highlighter),
        ToolEntry(Tool.BRUSH, "Brush", R.drawable.ic_tool_brush),
        ToolEntry(Tool.ERASER, "Eraser", R.drawable.ic_tool_eraser),
        ToolEntry(Tool.FILL, "Fill", R.drawable.ic_tool_fill),
        ToolEntry(Tool.TEXT, "Text", R.drawable.ic_tool_text),
        ToolEntry(Tool.LINE, "Line", R.drawable.ic_tool_line),
        ToolEntry(Tool.RECTANGLE, "Rectangle", R.drawable.ic_tool_rectangle),
        ToolEntry(Tool.CIRCLE, "Circle", R.drawable.ic_tool_circle),
        ToolEntry(Tool.TRIANGLE, "Triangle", R.drawable.ic_tool_triangle),
        ToolEntry(Tool.STAR, "Star", R.drawable.ic_tool_star),
        ToolEntry(Tool.IMAGE, "Image", R.drawable.ic_tool_image)
    )

    private val toolButtons = mutableMapOf<Tool, LinearLayout>()
    private val toolIcons = mutableMapOf<Tool, ImageView>()
    private lateinit var undoButton: LinearLayout
    private lateinit var redoButton: LinearLayout

    private fun makeToolButton(iconRes: Int, label: String, tint: Int? = null): Pair<LinearLayout, ImageView> {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val widthDp = if (deviceType == DeviceType.PHONE) 52f else 64f
            layoutParams = LinearLayout.LayoutParams(
                (widthDp * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(4, 8, 4, 8)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
            tint?.let { setColorFilter(it) }
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = if (deviceType == DeviceType.PHONE) 9.5f else 10.5f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, 4, 0, 0)
        }
        container.addView(icon)
        container.addView(text)
        return container to icon
    }

    private fun buildToolStrip() {
        val container = findViewById<LinearLayout>(R.id.toolStripInner)
        container.removeAllViews()
        toolButtons.clear()
        toolIcons.clear()

        for (entry in toolEntries) {
            val (btn, icon) = makeToolButton(entry.iconRes, entry.label)
            btn.setOnClickListener {
                if (entry.tool == Tool.IMAGE) {
                    imagePicker.launch("image/*")
                } else {
                    selectTool(entry.tool)
                }
            }
            toolButtons[entry.tool] = btn
            toolIcons[entry.tool] = icon
            container.addView(btn)
        }

        val (undo, undoIcon) = makeToolButton(R.drawable.ic_tool_undo, "Undo")
        undo.setOnClickListener { drawingView.undo() }
        undoButton = undo

        val (redo, redoIcon) = makeToolButton(R.drawable.ic_tool_redo, "Redo")
        redo.setOnClickListener { drawingView.redo() }
        redoButton = redo

        val (clear, _) = makeToolButton(R.drawable.ic_tool_clear, "Clear")
        (clear.getChildAt(1) as TextView).setTextColor(ContextCompat.getColor(this, R.color.record_red))
        clear.setOnClickListener { confirmClear() }

        container.addView(undo)
        container.addView(redo)
        container.addView(clear)

        selectTool(Tool.PEN)
        refreshUndoRedoState()
    }

    private fun selectTool(tool: Tool) {
        drawingView.currentTool = tool
        val selectedColor = ContextCompat.getColor(this, R.color.accent)
        val normalColor = ContextCompat.getColor(this, R.color.text_primary)
        for ((t, btn) in toolButtons) {
            val isSelected = t == tool
            btn.background = if (isSelected) ContextCompat.getDrawable(this, R.drawable.bg_tool_selected) else null
            toolIcons[t]?.setColorFilter(if (isSelected) selectedColor else normalColor)
        }
        if (tool == Tool.TEXT) {
            colorsPanelContent.visibility = View.GONE
            textOptionsPanel.visibility = View.VISIBLE
        } else {
            colorsPanelContent.visibility = View.VISIBLE
            textOptionsPanel.visibility = View.GONE
        }
    }

    private fun setupTextOptionsPanel() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fontOptions.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fontSpinner.adapter = adapter
        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFontFamily = fontOptions[position].second
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        textColorGrid.columnCount = 2
        textColorGrid.removeAllViews()
        for (color in defaultPalette) {
            val swatch = makeSwatch(color) { view ->
                selectedTextColor = color
                highlightSelected(view)
            }
            textColorGrid.addView(swatch)
        }

        findViewById<TextView>(R.id.btnExitTextMode).setOnClickListener {
            selectTool(Tool.PEN)
        }
    }

    private fun refreshUndoRedoState() {
        undoButton.alpha = if (drawingView.canUndo()) 1f else 0.4f
        redoButton.alpha = if (drawingView.canRedo()) 1f else 0.4f
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear the entire drawing?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> drawingView.clearAll() }
            .show()
    }

    // ---------------- Brush size ----------------

    private fun wireBrushSizeControls() {
        brushSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val density = resources.displayMetrics.density
                drawingView.currentStrokeWidth = (2 + progress * 0.58f) * density
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        findViewById<TextView>(R.id.sizeSmall).setOnClickListener { brushSizeSlider.progress = 10 }
        findViewById<TextView>(R.id.sizeMedium).setOnClickListener { brushSizeSlider.progress = 30 }
        findViewById<TextView>(R.id.sizeLarge).setOnClickListener { brushSizeSlider.progress = 55 }
        findViewById<TextView>(R.id.sizeXL).setOnClickListener { brushSizeSlider.progress = 85 }
    }

    // ---------------- Text tool ----------------

    private fun showTextInputDialog(x: Float, y: Float) {
        val input = EditText(this)
        input.hint = "Type your text"
        AlertDialog.Builder(this)
            .setTitle("Add Text")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    val textSize = 16f + (textSizeSlider.progress * 0.6f)
                    drawingView.addText(text, x, y, selectedTextColor, textSize, selectedFontFamily)
                }
            }
            .show()
    }

    // ---------------- Top actions: fullscreen / save / share / record ----------------

    private fun wireTopActions() {
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveDrawing() }
        findViewById<View>(R.id.btnShare).setOnClickListener { shareDrawing() }

        // Tapping the logo jumps straight to My Drawings; a long-press (or hover on a
        // device with a mouse/stylus hovering) shows a small "My Drawings" label first,
        // so it's discoverable without needing to tap the "More" menu.
        findViewById<View>(R.id.appTitle).apply {
            setOnClickListener { showMyDrawingsDialog() }
            androidx.core.view.ViewCompat.setTooltipText(this, "My Drawings")
        }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add("Full Screen")
        popup.menu.add("My Drawings")
        if (drawingView.isZoomedOrPanned()) {
            popup.menu.add("Reset Zoom")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Full Screen" -> toggleFullscreen()
                "My Drawings" -> showMyDrawingsDialog()
                "Reset Zoom" -> drawingView.resetZoom()
            }
            true
        }
        popup.show()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        leftPanel.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        toolStrip.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.appTitle).visibility = if (isFullscreen) View.GONE else View.VISIBLE
    }

    private fun saveDrawing(onSaved: (() -> Unit)? = null) {
        val bitmap = drawingView.exportBitmap() ?: return

        val input = EditText(this).apply {
            setText(SavedDrawingsStore.suggestedName())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Name this drawing")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { SavedDrawingsStore.suggestedName() }
                val uri = SaveUtil.savePngToGallery(this, bitmap)
                SavedDrawingsStore.saveProject(this, bitmap, name)
                if (uri != null) {
                    Toast.makeText(this, "Drawing saved", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                } else {
                    Toast.makeText(this, "Could not save the drawing.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareDrawing() {
        val bitmap = drawingView.exportBitmap() ?: return
        val uri = SaveUtil.saveTempPngForShare(this, bitmap)
        SaveUtil.shareFile(this, uri, "image/png")
    }

    // ---------------- My Drawings (reopen a saved drawing and keep working) ----------------

    private fun showMyDrawingsDialog() {
        val allProjects = SavedDrawingsStore.listProjects(this)
        if (allProjects.isEmpty()) {
            Toast.makeText(this, "No saved drawings yet. Tap Save to create one.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentList = allProjects.toMutableList()

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val searchBox = EditText(this).apply {
            hint = "Search by name"
        }
        val listView = ListView(this)
        dialogView.addView(searchBox)
        dialogView.addView(listView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("My Drawings")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = currentList.size
            override fun getItem(position: Int) = currentList[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_saved_drawing, parent, false)
                val file = currentList[position]
                val thumb = view.findViewById<ImageView>(R.id.thumbnail)
                val name = view.findViewById<TextView>(R.id.drawingName)
                val delete = view.findViewById<TextView>(R.id.deleteDrawing)

                SavedDrawingsStore.loadBitmap(file)?.let { thumb.setImageBitmap(it) }
                name.text = file.nameWithoutExtension

                view.setOnClickListener {
                    showDrawingActionsDialog(
                        file,
                        // Edit takes you to the canvas to draw — the list shouldn't pop
                        // back up afterward, so just close it.
                        onEdited = { dialog.dismiss() },
                        // Delete removes an item from this same list, so refresh it.
                        onDeleted = {
                            dialog.dismiss()
                            showMyDrawingsDialog()
                        }
                    )
                }
                delete.setOnClickListener {
                    SavedDrawingsStore.deleteProject(file)
                    dialog.dismiss()
                    showMyDrawingsDialog()
                }
                return view
            }
        }
        listView.adapter = adapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s.toString().trim().lowercase()
                currentList.clear()
                currentList.addAll(
                    if (query.isEmpty()) allProjects
                    else allProjects.filter { it.nameWithoutExtension.lowercase().contains(query) }
                )
                adapter.notifyDataSetChanged()
            }
        })

        dialog.show()
    }

    /** Shown when a saved drawing is tapped: View, Edit, Share, or Delete it. */
    private fun showDrawingActionsDialog(file: java.io.File, onEdited: () -> Unit, onDeleted: () -> Unit) {
        val options = arrayOf("View", "Edit", "Share", "Delete")
        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showDrawingPreviewDialog(file)
                    1 -> openForEditingWithOverwriteCheck(file, onEdited)
                    2 -> {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this, "$packageName.fileprovider", file
                        )
                        SaveUtil.shareFile(this, uri, "image/png")
                    }
                    3 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete this drawing?")
                            .setMessage("\"${file.nameWithoutExtension}\" will be permanently deleted.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete") { _, _ ->
                                SavedDrawingsStore.deleteProject(file)
                                onDeleted()
                            }
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Opening a saved drawing to edit it replaces whatever's currently on the canvas.
     * If there's unsaved work there already, confirm before wiping it out.
     */
    private fun openForEditingWithOverwriteCheck(file: java.io.File, onEdited: () -> Unit) {
        fun doLoad() {
            SavedDrawingsStore.loadBitmap(file)?.let { bmp -> drawingView.loadAsCanvasBackground(bmp) }
            onEdited()
        }
        if (drawingView.canUndo()) {
            AlertDialog.Builder(this)
                .setTitle("Discard current drawing?")
                .setMessage("Opening \"${file.nameWithoutExtension}\" will replace what's on the canvas right now. Anything unsaved will be lost.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Discard & Open") { _, _ -> doLoad() }
                .show()
        } else {
            doLoad()
        }
    }

    /** Full-size preview of a saved drawing. */
    private fun showDrawingPreviewDialog(file: java.io.File) {
        val bitmap = SavedDrawingsStore.loadBitmap(file) ?: return
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .show()
    }
}
