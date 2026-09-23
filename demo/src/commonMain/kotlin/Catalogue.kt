@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class CataloguePlatform(
    val name: String,
    val helloPage: @Composable () -> Unit,
    val webViewPage: @Composable () -> Unit,
    val videoPage: @Composable () -> Unit,
    val nativeViewsPage: @Composable () -> Unit,
    val windowsPage: @Composable ColumnScope.() -> Unit,
    val desktopPage: @Composable ColumnScope.() -> Unit,
)

private enum class Page(val title: String, val note: String) {
    Home("Catalogue", "Compose fork components, exercised for real"),
    Hello("Hello", "Platform accent color and system theme"),
    Resources("Resources", "Strings, vectors, and packaged fonts"),
    Controls("Buttons & Controls", "Buttons, choices, sliders and chips"),
    TextInputs("Text & Inputs", "Editing, selection, fonts and Unicode"),
    CardsLists("Cards & Lists", "Realistic scrollable content"),
    Navigation("Navigation", "Bars, tabs, rail and responsive layout"),
    Overlays("Dialogs & Overlays", "Dialogs, sheets, menus and pickers"),
    Graphics("Images & Graphics", "Images, gradients, paths and effects"),
    Animations("Animations", "Motion, loading and expansion"),
    WebView("WebView", "Platform web view integration"),
    Video("Video Player", "Platform video integration"),
    NativeViews("Native Views", "Platform interop surfaces"),
    Windows("Desktop Windows", "Window and desktop-host behavior"),
    Desktop("Desktop Features", "Clipboard, URLs, notifications and desktop integration"),
}

private data class CatalogueNavigationInfo(val page: Page) : NavigationEventInfo()
private val destinations = Page.entries.drop(1)

@Composable
internal fun CircleGlyph(contentDescription: String? = null, modifier: Modifier = Modifier) {
    val semanticsModifier =
        if (contentDescription == null) Modifier
        else Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    Box(
        modifier.then(semanticsModifier).size(18.dp)
            .background(LocalContentColor.current, CircleShape)
    )
}

@Composable
internal fun CatalogueApp(platform: CataloguePlatform) {
    var currentPage by remember { mutableStateOf(Page.Home) }
    val navigate: (Page) -> Unit = { currentPage = it }
    NavigationBackHandler(
        state = rememberNavigationEventState(
            currentInfo = CatalogueNavigationInfo(currentPage),
            backInfo = if (currentPage == Page.Home) emptyList()
            else listOf(CatalogueNavigationInfo(Page.Home)),
        ),
        isBackEnabled = currentPage != Page.Home,
        onBackCompleted = { currentPage = Page.Home },
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        Scaffold(
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        listOf(Page.Home, Page.Controls, Page.Graphics, Page.Desktop).forEach { page ->
                            NavigationBarItem(
                                selected = currentPage == page,
                                onClick = { navigate(page) },
                                icon = { CircleGlyph(page.title) },
                                label = { Text(if (page == Page.Home) "Home" else page.title.substringBefore(' ')) },
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) CatalogueRail(currentPage, navigate)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    key(currentPage) {
                        when (currentPage) {
                            Page.Home -> HomePage(platform.name, navigate)
                            Page.Hello -> platform.helloPage()
                            Page.Resources -> ResourceDemoPage(platform.name)
                            Page.Controls -> PageFrame(currentPage) { ControlsPage() }
                            Page.TextInputs -> PageFrame(currentPage) { TextInputsPage() }
                            Page.CardsLists -> CardsListsPage()
                            Page.Navigation -> PageFrame(currentPage) { NavigationPage() }
                            Page.Overlays -> PageFrame(currentPage) { OverlaysPage() }
                            Page.Graphics -> PageFrame(currentPage) { GraphicsPage() }
                            Page.Animations -> PageFrame(currentPage) { AnimationsPage() }
                            Page.WebView -> platform.webViewPage()
                            Page.Video -> platform.videoPage()
                            Page.NativeViews -> PageFrame(currentPage) { platform.nativeViewsPage() }
                            Page.Windows -> PageFrame(currentPage) { platform.windowsPage(this) }
                            Page.Desktop -> PageFrame(currentPage) { platform.desktopPage(this) }
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
        Text(
            "COMPONENT LAB",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
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
private fun HomePage(platformName: String, navigate: (Page) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Compose $platformName",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "A practical catalogue for shared UI plus platform-specific desktop integrations.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
internal fun DemoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
internal fun UnavailablePlatformPage(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.widthIn(max = 620.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button({}) { Text("Filled") }
            OutlinedButton({}) { Text("Outlined") }
            TextButton({}) { Text("Text") }
            IconButton({}) { CircleGlyph("Favorite") }
            FloatingActionButton({}) { CircleGlyph("Add") }
        }
    }
    DemoSection("Material 3 ripple") {
        Text(
            "Press, hover, or keyboard-focus these targets to exercise the new Material 3 " +
                "ripple implementation.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RippleDemoTarget(
                label = "Bounded",
                detail = "Touch origin · theme color",
                indication = ripple(bounded = true),
                modifier = Modifier.weight(1f),
            )
            RippleDemoTarget(
                label = "Unbounded",
                detail = "Centered · 42 dp radius",
                indication =
                    ripple(
                        bounded = false,
                        radius = 42.dp,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                modifier = Modifier.weight(1f),
            )
            RippleDemoTarget(
                label = "Press only",
                detail = "No hover / focus layer",
                indication =
                    ripple(
                        bounded = true,
                        color = MaterialTheme.colorScheme.tertiary,
                        enableFocusIndication = false,
                        enableHoverIndication = false,
                        enableDragIndication = false,
                    ),
                modifier = Modifier.weight(1f),
            )
        }
    }
    DemoSection("Choices") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { checked = it })
            Text("Checkbox")
            Spacer(Modifier.width(20.dp))
            Switch(switched, { switched = it })
            Spacer(Modifier.width(8.dp))
            Text("Switch")
        }
        Row {
            repeat(3) { index ->
                RadioButton(radio == index, { radio = index })
                Text("Option ${index + 1}", Modifier.padding(top = 12.dp, end = 12.dp))
            }
        }
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
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun RippleDemoTarget(
    label: String,
    detail: String,
    indication: androidx.compose.foundation.Indication,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var clicks by remember { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .height(104.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = { clicks++ },
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Clicks: $clicks",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TextInputsPage() {
    var name by remember { mutableStateOf("Ada Lovelace") }
    var search by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("compose") }
    var notes by remember {
        mutableStateOf(
            "Edit this text, select it, then copy and paste.\nUnicode: नमस्ते · 你好 · مرحباً · 👋🏽"
        )
    }
    DemoSection("Type scale & fonts") {
        Text("Display", style = MaterialTheme.typography.displaySmall)
        Text("Headline / system sans", style = MaterialTheme.typography.headlineSmall)
        Text("Monospace: val linux = Compose()", fontFamily = FontFamily.Monospace)
    }
    DemoSection("Fields") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name editor" },
        )
        OutlinedTextField(
            search,
            { search = it },
            label = { Text("Search") },
            leadingIcon = { CircleGlyph() },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            notes,
            { notes = it },
            label = { Text("Multiline editor") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    DemoSection("Selection & Unicode") {
        SelectionContainer { Text("Select and copy: café · Ελληνικά · 日本語 · 🚀") }
    }
}

@Composable
private fun CardsListsPage() {
    val baseContacts =
        listOf(
            "Ada Lovelace" to "Computing",
            "Linus Torvalds" to "Linux",
            "Margaret Hamilton" to "Apollo",
            "Grace Hopper" to "Compilers",
            "James Gosling" to "Java",
            "Radia Perlman" to "Networks",
        )
    var refreshCount by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val contacts =
        if (refreshCount == 0) {
            baseContacts
        } else {
            listOf("New contact #$refreshCount" to "Just refreshed") + baseContacts
        }
    val onRefresh: () -> Unit = {
        if (!isRefreshing) {
            isRefreshing = true
            scope.launch {
                delay(900)
                refreshCount++
                isRefreshing = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleGlyph("Contacts illustration")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Cards & Lists",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Pull with touch/trackpad, or use Refresh with a mouse.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text(if (isRefreshing) "Refreshing…" else "Refresh")
            }
        }
        Spacer(Modifier.height(14.dp))
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = refreshState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = refreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    AccessibleRuntimeTable()
                    Spacer(Modifier.height(10.dp))
                }
                items(contacts) { (name, role) ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    name.take(1),
                                    Modifier.padding(14.dp),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.Medium)
                                Text(role, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Badge { Text("${name.length}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessibleRuntimeTable() {
    val cells = listOf("Renderer" to "Skia Ganesh + OpenGL", "Accessibility" to "AT-SPI")
    Surface(
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = "Runtime matrix"
                collectionInfo = CollectionInfo(rowCount = 2, columnCount = 2)
            },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.forEachIndexed { rowIndex, (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeTableCell(label, rowIndex, columnIndex = 0, Modifier.weight(1f))
                    RuntimeTableCell(value, rowIndex, columnIndex = 1, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RuntimeTableCell(text: String, rowIndex: Int, columnIndex: Int, modifier: Modifier) {
    Surface(
        modifier =
            modifier.semantics {
                contentDescription = "Runtime cell $rowIndex,$columnIndex: $text"
                collectionItemInfo =
                    CollectionItemInfo(
                        rowIndex = rowIndex,
                        rowSpan = 1,
                        columnIndex = columnIndex,
                        columnSpan = 1,
                    )
            },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(text, Modifier.padding(10.dp))
    }
}

@Composable
private fun NavigationPage() {
    var tab by remember { mutableIntStateOf(0) }
    var drawer by remember { mutableStateOf(false) }
    DemoSection("Top bar & tabs") {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sample inbox",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton({ drawer = !drawer }) { Text("Menu") }
                }
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("Primary", "Updates", "Saved").forEachIndexed { index, text ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(text) },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                    Text("${listOf("Primary", "Updates", "Saved")[tab]} content")
                }
            }
        }
    }
    DemoSection("Responsive destinations") {
        Text(
            "Resize the main window: this catalogue switches between a sidebar and bottom navigation."
        )
        if (drawer)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Drawer: Home  ·  Inbox  ·  Settings", Modifier.padding(18.dp))
            }
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
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button({ alert = true }) { Text("Alert") }
            Button({ sheet = true }) { Text("Bottom sheet") }
            Box {
                Button({ menu = true }) { Text("Menu") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false })
                    DropdownMenuItem({ Text("Delete") }, { menu = false })
                }
            }
            Button({ date = true }) { Text("Date picker") }
            Button({ scope.launch { snackbar.showSnackbar("Saved to the catalogue") } }) {
                Text("Snackbar")
            }
        }
        Text("Tip: hover and focus states are visible across the controls.")
        SnackbarHost(snackbar)
    }
    if (alert)
        AlertDialog(
            { alert = false },
            confirmButton = { TextButton({ alert = false }) { Text("Continue") } },
            dismissButton = { TextButton({ alert = false }) { Text("Cancel") } },
            title = { Text("Remove item?") },
            text = { Text("This is a native Compose alert dialog.") },
        )
    if (sheet)
        ModalBottomSheet({ sheet = false }) {
            Column(Modifier.fillMaxWidth().padding(28.dp)) {
                Text("Bottom sheet", style = MaterialTheme.typography.headlineSmall)
                Text("Menus and actions can live here.")
                Spacer(Modifier.height(32.dp))
            }
        }
    if (date) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            { date = false },
            confirmButton = { TextButton({ date = false }) { Text("Choose") } },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
private fun GraphicsPage() {
    var tilt by remember { mutableFloatStateOf(8f) }
    DemoSection("PNG · JPEG · WebP gallery") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                    "PNG" to Color(0xff6750a4),
                    "JPEG" to Color(0xff006c4c),
                    "WebP" to Color(0xff984061),
                )
                .forEach { (label, color) ->
                    Box(
                        Modifier.weight(1f)
                            .height(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(listOf(color, color.copy(alpha = .35f)))
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontWeight = FontWeight.Bold)
                    }
                }
        }
    }
    DemoSection("Gradients, vector paths & transforms") {
        Canvas(
            Modifier.fillMaxWidth()
                .height(210.dp)
                .graphicsLayer {
                    rotationY = tilt
                    shadowElevation = 18f
                }
                .blur(0.3.dp)
        ) {
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xff7c4dff), Color(0xff00bfa5))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(36f, 36f),
            )
            val path =
                Path().apply {
                    moveTo(size.width * .18f, size.height * .72f)
                    quadraticTo(size.width * .48f, -20f, size.width * .82f, size.height * .7f)
                    lineTo(size.width * .65f, size.height * .82f)
                    quadraticTo(
                        size.width * .48f,
                        size.height * .25f,
                        size.width * .33f,
                        size.height * .82f,
                    )
                    close()
                }
            drawPath(path, Color.White.copy(alpha = .78f))
        }
        Text("3D tilt ${tilt.roundToInt()}°")
        Slider(tilt, { tilt = it }, valueRange = -30f..30f)
    }
}

@Composable
private fun AnimationsPage() {
    var expanded by remember { mutableStateOf(false) }
    var motion by remember { mutableFloatStateOf(.45f) }
    val scale by animateFloatAsState(if (expanded) 1.03f else .96f)
    DemoSection("Animated card") {
        Card(
            Modifier.fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable { expanded = !expanded }
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    if (expanded) "Tap to collapse" else "Tap to expand",
                    fontWeight = FontWeight.Bold,
                )
                AnimatedVisibility(expanded) {
                    Text(
                        "Content enters smoothly while Compose keeps the state.",
                        Modifier.padding(top = 12.dp),
                    )
                }
            }
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
                Box(contentAlignment = Alignment.Center) { CircleGlyph("Motion position") }
            }
        }
        Slider(motion, { motion = it })
    }
}
