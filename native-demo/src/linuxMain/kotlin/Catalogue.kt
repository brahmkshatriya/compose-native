@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.InteropRenderTarget
import androidx.compose.ui.viewinterop.LinuxInteropView
import androidx.compose.ui.viewinterop.NativeView
import androidx.compose.ui.viewinterop.OpenGlInteropRenderTarget
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.NotificationAction
import androidx.compose.ui.window.NotificationRequest
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.sendNotification
import app.webview.app_demo_render_gl
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Page(val title: String, val note: String) {
    Home("Catalogue", "Linux Compose, exercised for real"),
    Controls("Buttons & Controls", "Buttons, choices, sliders and chips"),
    TextInputs("Text & Inputs", "Editing, selection, fonts and Unicode"),
    CardsLists("Cards & Lists", "Realistic scrollable content"),
    Navigation("Navigation", "Bars, tabs, rail and responsive layout"),
    Overlays("Dialogs & Overlays", "Dialogs, sheets, menus and pickers"),
    Graphics("Images & Graphics", "Images, gradients, paths and effects"),
    Animations("Animations", "Motion, loading and expansion"),
    WebView("WebView", "WPE WebKit with browser controls"),
    Video("Video Player", "Native MPV video surface"),
    NativeViews("Native Views", "CPU and OpenGL interop surfaces"),
    Windows("Desktop Windows", "Multi-window and window modes"),
    Desktop("Desktop Features", "Notifications, clipboard, URLs and drops"),
}

private val destinations = Page.entries.drop(1)

@Composable
private fun CircleGlyph(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val semanticsModifier =
        if (contentDescription == null) Modifier
        else Modifier.semantics { this.contentDescription = contentDescription }

    Box(
        modifier
            .then(semanticsModifier)
            .size(18.dp)
            .background(LocalContentColor.current, CircleShape),
    )
}

@Composable
fun WindowScope.CatalogueApp(onOpenPreview: () -> Unit) {
    var currentPage by remember { mutableStateOf(Page.Home) }
    val navigate: (Page) -> Unit = { page -> currentPage = page }
    BackHandler(enabled = currentPage != Page.Home) { currentPage = Page.Home }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        Scaffold(
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        listOf(Page.Home, Page.Controls, Page.WebView, Page.NativeViews).forEach { page ->
                            NavigationBarItem(
                                selected = currentPage == page,
                                onClick = { navigate(page) },
                                icon = { CircleGlyph(page.title) },
                                label = { Text(if (page == Page.Home) "Home" else page.title.substringBefore(' ')) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) CatalogueRail(currentPage, navigate)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    key(currentPage) {
                        when (currentPage) {
                            Page.Home -> HomePage(navigate)
                            Page.Controls -> PageFrame(currentPage) { ControlsPage() }
                            Page.TextInputs -> PageFrame(currentPage) { TextInputsPage() }
                            Page.CardsLists -> CardsListsPage()
                            Page.Navigation -> PageFrame(currentPage) { NavigationPage() }
                            Page.Overlays -> PageFrame(currentPage) { OverlaysPage() }
                            Page.Graphics -> PageFrame(currentPage) { GraphicsPage() }
                            Page.Animations -> PageFrame(currentPage) { AnimationsPage() }
                            Page.WebView -> WebViewPage()
                            Page.Video -> VideoPage()
                            Page.NativeViews -> PageFrame(currentPage) { NativeViewsPage() }
                            Page.Windows -> PageFrame(currentPage) { WindowsPage(onOpenPreview) }
                            Page.Desktop -> PageFrame(currentPage) { DesktopPage() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogueRail(selected: Page, navigate: (Page) -> Unit) {
    NavigationRail(
        modifier = Modifier.width(210.dp).fillMaxHeight().verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(12.dp))
        Text("COMPONENT LAB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        NavigationRailItem(
            selected = selected == Page.Home,
            onClick = { navigate(Page.Home) },
            icon = { CircleGlyph("Overview") },
            label = { Text("Overview") },
        )
        destinations.forEach { page ->
            NavigationRailItem(
                selected = selected == page,
                onClick = { navigate(page) },
                icon = { CircleGlyph(page.title) },
                label = { Text(page.title, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun HomePage(navigate: (Page) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Compose Linux", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("A practical catalogue for UI, native interop and desktop features.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        items(destinations.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { page -> CatalogueCard(page, Modifier.weight(1f)) { navigate(page) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CatalogueCard(page: Page, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                CircleGlyph(modifier = Modifier.padding(15.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(page.title, style = MaterialTheme.typography.titleMedium)
                Text(page.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PageFrame(page: Page, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Text(page.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(page.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        content()
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun DemoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ControlsPage() {
    var checked by remember { mutableStateOf(true) }
    var switched by remember { mutableStateOf(false) }
    var radio by remember { mutableIntStateOf(0) }
    var slider by remember { mutableFloatStateOf(42f) }
    var chip by remember { mutableStateOf(false) }
    var segment by remember { mutableIntStateOf(1) }
    DemoSection("Buttons") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({}) { Text("Filled") }
            OutlinedButton({}) { Text("Outlined") }
            TextButton({}) { Text("Text") }
            IconButton({}) { CircleGlyph("Favorite") }
            FloatingActionButton({}) { CircleGlyph("Add") }
        }
    }
    DemoSection("Choices") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { checked = it }); Text("Checkbox")
            Spacer(Modifier.width(20.dp)); Switch(switched, { switched = it }); Spacer(Modifier.width(8.dp)); Text("Switch")
        }
        Row { repeat(3) { index -> RadioButton(radio == index, { radio = index }); Text("Option ${index + 1}", Modifier.padding(top = 12.dp, end = 12.dp)) } }
    }
    DemoSection("Sliders, chips & segments") {
        Text("Value ${slider.roundToInt()}")
        Slider(slider, { slider = it }, valueRange = 0f..100f)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(chip, { chip = !chip }, { Text("Selectable chip") })
            AssistChip({}, { Text("Assist chip") })
        }
        SingleChoiceSegmentedButtonRow {
            listOf("Day", "Week", "Month").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = segment == index,
                    onClick = { segment = index },
                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun TextInputsPage() {
    var name by remember { mutableStateOf("Ada Lovelace") }
    var search by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("compose") }
    var notes by remember { mutableStateOf("Edit this text, select it, then copy and paste.\nUnicode: नमस्ते · 你好 · مرحباً · 👋🏽") }
    DemoSection("Type scale & fonts") {
        Text("Display", style = MaterialTheme.typography.displaySmall)
        Text("Headline / system sans", style = MaterialTheme.typography.headlineSmall)
        Text("Monospace: val linux = Compose()", fontFamily = FontFamily.Monospace)
    }
    DemoSection("Fields") {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, leadingIcon = { CircleGlyph() }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Multiline editor") }, minLines = 4, modifier = Modifier.fillMaxWidth())
    }
    DemoSection("Selection & Unicode") { SelectionContainer { Text("Select and copy: café · Ελληνικά · 日本語 · 🚀") } }
}

@Composable
private fun CardsListsPage() {
    val contacts = listOf("Ada Lovelace" to "Computing", "Linus Torvalds" to "Linux", "Margaret Hamilton" to "Apollo", "Grace Hopper" to "Compilers", "James Gosling" to "Java", "Radia Perlman" to "Networks")
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Text("Cards & Lists", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("A contacts screen using a real lazy list.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(contacts) { (name, role) ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Text(name.take(1), Modifier.padding(14.dp), fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Medium); Text(role, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Badge { Text("${name.length}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationPage() {
    var tab by remember { mutableIntStateOf(0) }
    var drawer by remember { mutableStateOf(false) }
    DemoSection("Top bar & tabs") {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Sample inbox", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton({ drawer = !drawer }) { Text("Menu") } }
                TabRow(tab) { listOf("Primary", "Updates", "Saved").forEachIndexed { i, text -> Tab(tab == i, { tab = i }, text = { Text(text) }) } }
                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { Text("${listOf("Primary", "Updates", "Saved")[tab]} content") }
            }
        }
    }
    DemoSection("Responsive destinations") {
        Text("Resize the main window: this catalogue switches between a sidebar and bottom navigation.")
        if (drawer) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) { Text("Drawer: Home  ·  Inbox  ·  Settings", Modifier.padding(18.dp)) }
        NavigationBar {
            val labels = listOf("Home", "Inbox", "Settings")
            repeat(3) { index ->
                NavigationBarItem(
                    selected = index == tab,
                    onClick = { tab = index },
                    icon = { CircleGlyph(labels[index]) },
                    label = { Text(labels[index]) },
                )
            }
        }
    }
}

@Composable
private fun OverlaysPage() {
    var alert by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    DemoSection("Open an overlay") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ alert = true }) { Text("Alert") }; Button({ sheet = true }) { Text("Bottom sheet") }
            Box { Button({ menu = true }) { Text("Menu") }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("Duplicate") }, { menu = false }); DropdownMenuItem({ Text("Delete") }, { menu = false }) } }
            Button({ date = true }) { Text("Date picker") }
            Button({ scope.launch { snackbar.showSnackbar("Saved to the catalogue") } }) { Text("Snackbar") }
        }
        Text("Tip: hover and focus states are visible across the controls.")
        SnackbarHost(snackbar)
    }
    if (alert) AlertDialog({ alert = false }, confirmButton = { TextButton({ alert = false }) { Text("Continue") } }, dismissButton = { TextButton({ alert = false }) { Text("Cancel") } }, title = { Text("Remove item?") }, text = { Text("This is a native Compose alert dialog.") })
    if (sheet) ModalBottomSheet({ sheet = false }) { Column(Modifier.fillMaxWidth().padding(28.dp)) { Text("Bottom sheet", style = MaterialTheme.typography.headlineSmall); Text("Menus and actions can live here."); Spacer(Modifier.height(32.dp)) } }
    if (date) {
        val state = rememberDatePickerState()
        DatePickerDialog({ date = false }, confirmButton = { TextButton({ date = false }) { Text("Choose") } }) { DatePicker(state) }
    }
}

@Composable
private fun GraphicsPage() {
    var tilt by remember { mutableFloatStateOf(8f) }
    DemoSection("PNG · JPEG · WebP gallery") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("PNG" to Color(0xff6750a4), "JPEG" to Color(0xff006c4c), "WebP" to Color(0xff984061)).forEach { (label, color) ->
                Box(Modifier.weight(1f).height(120.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = .35f)))), contentAlignment = Alignment.Center) { Text(label, fontWeight = FontWeight.Bold) }
            }
        }
    }
    DemoSection("Gradients, vector paths & transforms") {
        Canvas(Modifier.fillMaxWidth().height(210.dp).graphicsLayer { rotationY = tilt; shadowElevation = 18f }.blur(0.3.dp)) {
            drawRoundRect(Brush.linearGradient(listOf(Color(0xff7c4dff), Color(0xff00bfa5))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(36f, 36f))
            val path = Path().apply { moveTo(size.width * .18f, size.height * .72f); quadraticBezierTo(size.width * .48f, -20f, size.width * .82f, size.height * .7f); lineTo(size.width * .65f, size.height * .82f); quadraticBezierTo(size.width * .48f, size.height * .25f, size.width * .33f, size.height * .82f); close() }
            drawPath(path, Color.White.copy(alpha = .78f))
        }
        Text("3D tilt ${tilt.roundToInt()}°"); Slider(tilt, { tilt = it }, valueRange = -30f..30f)
    }
}

@Composable
private fun AnimationsPage() {
    var expanded by remember { mutableStateOf(false) }
    var motion by remember { mutableFloatStateOf(.45f) }
    val scale by animateFloatAsState(if (expanded) 1.03f else .96f)
    DemoSection("Animated card") {
        Card(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.clickable { expanded = !expanded }) {
            Column(Modifier.padding(18.dp)) { Text(if (expanded) "Tap to collapse" else "Tap to expand", fontWeight = FontWeight.Bold); AnimatedVisibility(expanded) { Text("Content enters smoothly while Compose keeps the state.", Modifier.padding(top = 12.dp)) } }
        }
    }
    DemoSection("Loading & interactive motion") {
        Text("Indeterminate loading", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text("Circular", style = MaterialTheme.typography.labelMedium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator(Modifier.size(48.dp))
                Text("Morphing", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("Interactive motion", style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { motion }, Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth().height(70.dp)) {
            val markerSize = 52.dp
            Surface(
                Modifier.offset(x = (maxWidth - markerSize) * motion).size(markerSize),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircleGlyph("Motion position")
                }
            }
        }
        Slider(motion, { motion = it })
    }
}

@Composable
private fun WebViewPage() {
    val initial = "https://www.youtube.com/"
    val browser = remember { WpeBrowser(initial) }
    var address by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ browser.back() }) { CircleGlyph("Back") }
            IconButton({ browser.forward() }) { CircleGlyph("Forward") }
            IconButton({ browser.reload() }) { CircleGlyph("Reload") }
            OutlinedTextField(address, { address = it }, Modifier.weight(1f), singleLine = true)
            Button({ browser.load(if ("://" in address) address else "https://$address") }) { Text("Go") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            NativeView({ browser.nativeView }, Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.BottomEnd).padding(14.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) { Text("Compose overlay · WPE WebKit", Modifier.padding(10.dp)) }
        }
    }
}

@Composable
private fun VideoPage() {
    val player = remember { MpvPlayer("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") }
    var playing by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(.8f) }
    LaunchedEffect(player) {
        for (position in player.positionUpdates) {
            if (!seeking) seek = position
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        NativeView({ player.nativeView }, Modifier.fillMaxSize())
        Surface(Modifier.align(Alignment.TopStart).padding(16.dp), color = Color.Black.copy(alpha = .72f), shape = RoundedCornerShape(10.dp)) { Text("Native MPV · HLS stream", Modifier.padding(10.dp), color = Color.White) }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp), color = Color.Black.copy(alpha = .82f), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Button({ playing = !playing; player.setPlaying(playing) }) { Text(if (playing) "Pause" else "Play") }; Spacer(Modifier.width(12.dp)); Text("Mux HLS test stream", Modifier.weight(1f)); Text("CC  ⋮  ⛶") }
                Slider(
                    value = seek,
                    onValueChange = {
                        seeking = true
                        seek = it
                    },
                    onValueChangeFinished = {
                        player.seek(seek)
                        seeking = false
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Volume"); Slider(volume, { volume = it; player.volume(it) }, Modifier.width(220.dp)); Text("HLS · MPV") }
            }
        }
    }
}

@Composable
private fun NativeViewsPage() {
    var size by remember { mutableFloatStateOf(190f) }
    var phase by remember { mutableFloatStateOf(0f) }
    val cpuView = remember { LinuxInteropView(renderer = ::renderCpuDemo) }
    val phaseHolder = remember { FloatArray(1) { phase } }
    val glView = remember {
        LinuxInteropView.openGl(
            renderer = { target: OpenGlInteropRenderTarget ->
                app_demo_render_gl(
                    target.framebuffer,
                    target.width,
                    target.height,
                    phaseHolder[0],
                )
                true
            },
        )
    }
    LaunchedEffect(phase, glView) {
        phaseHolder[0] = phase
        glView.requestRender()
    }
    DemoSection("Two native surfaces in Compose") {
        val cornerRadius = with(LocalDensity.current) { 24.dp.toPx() }
        val nativeClip: (Size) -> Path = { viewSize ->
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = viewSize.width,
                        bottom = viewSize.height,
                        cornerRadius = CornerRadius(cornerRadius),
                    )
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NativeView(
                { cpuView },
                Modifier.weight(1f).height(size.dp).clip(RoundedCornerShape(24.dp)).graphicsLayer { rotationZ = -2f },
                clipPath = nativeClip,
            )
            NativeView(
                { glView },
                Modifier.weight(1f).height(size.dp).clip(RoundedCornerShape(24.dp)).graphicsLayer { rotationZ = 2f },
                clipPath = nativeClip,
            )
        }
        Row { AssistChip({}, { Text("CPU pixels") }); Spacer(Modifier.width(8.dp)); AssistChip({}, { Text("Native OpenGL") }) }
        Text("Animated size / OpenGL color"); Slider(size, { size = it; phase = it / 30f }, valueRange = 120f..300f)
    }
}

private fun renderCpuDemo(target: InteropRenderTarget): Boolean {
    val pixels = target.pixels.reinterpret<UByteVar>()
    for (y in 0 until target.height) for (x in 0 until target.width) {
        val offset = y * target.stride + x * 4
        pixels[offset] = (80 + x * 150 / target.width).toUByte()
        pixels[offset + 1] = (40 + y * 180 / target.height).toUByte()
        pixels[offset + 2] = 210u
        pixels[offset + 3] = 255u
    }
    return true
}

@Composable
private fun WindowScope.WindowsPage(onOpenPreview: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    DemoSection("Window playground") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onOpenPreview) { Text("Open preview window") }; OutlinedButton({ dialog = true }) { Text("Open dialog") } }
        Text("The preview is resizable and always on top. Double-click the system title bar to maximize; use your desktop shortcut for fullscreen.")
        WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Custom title-bar preview  ·  drag area",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
    if (dialog) AlertDialog({ dialog = false }, confirmButton = { Button({ dialog = false }) { Text("Done") } }, title = { Text("Compose dialog") }, text = { Text("Dialogs remain owned by the active window.") })
}

@Composable
private fun DesktopPage() {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var copied by remember { mutableStateOf("Nothing copied yet") }
    var progress by remember { mutableFloatStateOf(.25f) }
    DemoSection("Desktop actions") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ sendNotification(Notification("Compose Linux", "The catalogue notification works.", Notification.Type.Info)) }) { Text("Send notification") }
            OutlinedButton({ sendNotification(NotificationRequest(title = "Copying files", message = "Project assets", progress = progress, actions = listOf(NotificationAction("cancel", "Cancel")))) }) { Text("Progress notification") }
            OutlinedButton({ clipboard.setText(AnnotatedString("Hello from Compose Linux")); copied = "Copied to clipboard" }) { Text("Copy text") }
            OutlinedButton({ uri.openUri("https://kotlinlang.org") }) { Text("Open URL") }
        }
        Text(copied); Slider(progress, { progress = it })
    }
    DemoSection("Drag & keyboard") {
        Box(Modifier.fillMaxWidth().height(120.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text("Drop files or text anywhere in this window") }
        Text("Keyboard shortcuts:  Ctrl+C copy  ·  Ctrl+V paste  ·  Tab focus  ·  Esc close")
    }
}

@Composable
fun PreviewWindow() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xff1f1635), Color(0xff073b4c)))), contentAlignment = Alignment.Center) {
        Card(Modifier.padding(36.dp)) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("Live preview", style = MaterialTheme.typography.headlineMedium); Text("A second native Linux window"); Spacer(Modifier.height(16.dp)); CircularProgressIndicator() } }
    }
}
