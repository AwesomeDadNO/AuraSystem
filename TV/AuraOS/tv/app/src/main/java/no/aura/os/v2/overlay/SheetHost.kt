package no.aura.os.v2.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.input.key.*
import no.aura.os.v2.settings.CodeInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.profiles.PinPolicy
import no.aura.os.v2.settings.Sheet
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/** Renders a [Sheet] in its own dialog window (focus is trapped; the first control takes focus; Back closes). Same look as [DemoDialogHost]. */
@Composable
fun SheetHost(sheet: Sheet, onClose: () -> Unit) {
    val first = remember(sheet) { FocusRequester() }
    CanvasDialog(onClose) {
        // The first control takes focus from INSIDE the dialog composition, one frame after it exists. (Requested from the host composition it
        // could run before the dialog window had laid out anything, fail silently and leave the sheet without focus.) The code sheet focuses
        // its own text field, because that also has to wait for the window to gain input focus.
        if (sheet !is Sheet.CodeEntry && sheet !is Sheet.TextEntry) LaunchedEffect(sheet) { withFrameNanos { }; runCatching { first.requestFocus() } }
        Column(
            Modifier.clip(RoundedCornerShape(AuraDimensions.RadiusContainer)).background(AuraColors.SurfaceElevated).heightIn(max = 880.dp).androidx_vscroll().padding(AuraDimensions.Space32).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(AuraDimensions.Space12),
        ) {
            when (sheet) {
                is Sheet.Message -> {
                    Text(sheet.title, style = AuraTypography.SectionTitle)
                    sheet.lines.forEach { Text(it, style = AuraTypography.Body, color = AuraColors.TextSecondary) }
                    SheetButton("Close", Modifier.focusRequester(first), primary = true, onClick = onClose)
                }
                is Sheet.Choice -> {
                    Text(sheet.title, style = AuraTypography.SectionTitle)
                    sheet.note?.let { Text(it, style = AuraTypography.Label, color = AuraColors.TextSecondary) }
                    sheet.options.forEachIndexed { i, o -> SheetButton(o.label, if (i == 0) Modifier.focusRequester(first) else Modifier, primary = false, onClick = o.onPick) }
                    SheetButton("Cancel", Modifier, primary = false, onClick = onClose)
                }
                is Sheet.AuraConnectSheet -> {
                    Text(sheet.title, style = AuraTypography.SectionTitle)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val qrBitmap = remember(sheet.qrData) {
                            try {
                                val size = 300
                                val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(sheet.qrData, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
                                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
                                for (x in 0 until size) {
                                    for (y in 0 until size) {
                                        bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                                    }
                                }
                                bmp.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (qrBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = qrBitmap,
                                contentDescription = "QR Code for pairing",
                                modifier = Modifier.size(160.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PIN: ${sheet.code.chunked(3).joinToString(" ")}", style = AuraTypography.SectionTitle.copy(fontSize = 28.sp, color = AuraColors.BrandOrange))
                            Text("AuraOS TV", style = AuraTypography.CardTitle)
                            Text("${sheet.host}:${sheet.port}", style = AuraTypography.Metadata, color = AuraColors.TextSecondary)
                            if (sheet.activeModules.isNotEmpty()) {
                                Text("Active modules: ${sheet.activeModules.joinToString(", ")}", style = AuraTypography.Label, color = AuraColors.Success)
                            } else {
                                Text("No active modules", style = AuraTypography.Label, color = AuraColors.AlertRed)
                            }
                        }
                    }
                    SheetButton("New code", Modifier.focusRequester(first), primary = false, onClick = sheet.onNewCode)
                    SheetButton("Paired devices", Modifier, primary = false, onClick = sheet.onPairedDevices)
                    if (sheet.onDeezer != null) {
                        SheetButton("Receive Deezer sign-in", Modifier, primary = false, onClick = sheet.onDeezer)
                    }
                    if (sheet.onMediaServer != null) {
                        SheetButton("Receive media server sign-in", Modifier, primary = false, onClick = sheet.onMediaServer)
                    }
                    SheetButton("Close", Modifier, primary = true, onClick = onClose)
                }
                is Sheet.PinEntry -> PinPad(sheet, first, onClose)
                is Sheet.CodeEntry -> CodePad(sheet, first, onClose)
                is Sheet.TextEntry -> TextPad(sheet, first, onClose)
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, modifier: Modifier, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
    Text(
        text, style = AuraTypography.Button, color = if (primary) AuraColors.OnPrimaryAction else AuraColors.TextPrimary, maxLines = 1,
        modifier = modifier.fillMaxWidth().auraFocusable(shape, onClick = onClick).clip(shape).background(AuraColors.ButtonInactive).padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/** DPAD digit pad. The entered digits are held only in this composable's state and are never logged. */
@Composable
private fun PinPad(sheet: Sheet.PinEntry, first: FocusRequester, onClose: () -> Unit) {
    var digits by remember(sheet) { mutableStateOf("") }
    var busy by remember(sheet) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
    Text(sheet.title, style = AuraTypography.SectionTitle)
    Text("•".repeat(digits.length).ifEmpty { "Enter ${PinPolicy.MIN}–${PinPolicy.MAX} digits" }, style = AuraTypography.SectionTitle, color = AuraColors.TextSecondary)
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
    keys.chunked(3).forEachIndexed { r, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEachIndexed { c, k ->
                Text(
                    k, style = AuraTypography.Button.copy(fontSize = 22.sp), textAlign = TextAlign.Center, color = AuraColors.TextPrimary,
                    modifier = Modifier.then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier).width(90.dp)
                        .auraFocusable(shape) {
                            when (k) {
                                "⌫" -> digits = digits.dropLast(1)
                                // PBKDF2 hashing is deliberately slow: never run it on the main thread. busy blocks re-entry.
                                "OK" -> if (!busy && PinPolicy.isValid(digits)) { val d = digits; digits = ""; busy = true; scope.launch { withContext(Dispatchers.Default) { sheet.onSubmit(d) }; busy = false } }
                                else -> if (!busy && digits.length < PinPolicy.MAX) digits += k
                            }
                        }.clip(shape).background(if (k == "OK") AuraColors.NavPill else AuraColors.Surface).padding(vertical = 12.dp),
                )
            }
        }
    }
    Text("Cancel", style = AuraTypography.Button, color = AuraColors.TextPrimary, modifier = Modifier.fillMaxWidth().auraFocusable(shape, onClick = onClose).clip(shape).background(AuraColors.Surface).padding(horizontal = 24.dp, vertical = 12.dp))
}

/** Upper-cases a-z for display only (same length, so cursor/selection offsets are unchanged). The stored text is never rewritten. */
private val UpperCaseDisplay = VisualTransformation { t ->
    TransformedText(AnnotatedString(String(CharArray(t.text.length) { i -> t.text[i].let { c -> if (c in 'a'..'z') c.uppercaseChar() else c } })), OffsetMapping.Identity)
}

/**
 * UltraMax code entry with the Android TV system keyboard (IME): text keyboard, automatic capitals, no suggestions, paste supported.
 *
 * Rules that make the IME reliable on a TV (F6c):
 * - The field keeps EXACTLY what the keyboard produced (a [TextFieldValue] with its selection and composing region). Rewriting the text in
 *   onValueChange (upper-casing, stripping characters) while the keyboard is composing desynchronises it and restarts the input session on
 *   every key, so normalisation is applied to a derived value ([CodeInput.normalize]) and upper-casing is visual only.
 * - Focus goes to the field only once the dialog WINDOW has input focus; the keyboard is then requested. OK (DPAD centre) on the field
 *   opens the keyboard again; only a centre press that started on the field counts, so the OK that opened this sheet cannot leak in.
 * - DPAD down always leaves the field for Save; the keyboard's Done hides it and moves to Save. Back closes the keyboard first (platform),
 *   then the sheet.
 * The text is held only in this composable's state and is never logged.
 */
/**
 * Free-text entry with the TV system keyboard (server address, user name, password). Same IME rules as the code field: the field keeps the
 * keyboard's own text, focus waits for the dialog window, OK reopens the keyboard, DPAD down / Done go to Continue. A password is masked
 * and exists only in this composable's state until Continue hands it to the flow.
 */
@Composable
private fun TextPad(sheet: Sheet.TextEntry, first: FocusRequester, onClose: () -> Unit) {
    var field by remember(sheet) { mutableStateOf(TextFieldValue("")) }
    val secret = sheet.keyboard == Sheet.Keyboard.PASSWORD
    val value = if (secret) field.text else field.text.trim()
    val ready = sheet.allowEmpty || value.isNotEmpty()
    val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val next = remember(sheet) { FocusRequester() }
    var fieldFocused by remember(sheet) { mutableStateOf(false) }
    LaunchedEffect(sheet, windowFocused) { if (windowFocused) { withFrameNanos { }; runCatching { first.requestFocus() }; delay(120); keyboard?.show() } }
    Text(sheet.title, style = AuraTypography.SectionTitle)
    sheet.note?.let { Text(it, style = AuraTypography.Label, color = AuraColors.TextSecondary) }
    BasicTextField(
        value = field, onValueChange = { v -> if (v.text.length <= sheet.maxLength) field = v }, singleLine = true,
        textStyle = AuraTypography.Body.copy(color = AuraColors.TextPrimary), cursorBrush = SolidColor(AuraColors.Focus),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            keyboardType = when (sheet.keyboard) { Sheet.Keyboard.URL -> KeyboardType.Uri; Sheet.Keyboard.PASSWORD -> KeyboardType.Password; Sheet.Keyboard.TEXT -> KeyboardType.Ascii }, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); runCatching { next.requestFocus() } }),
        modifier = Modifier.fillMaxWidth().focusRequester(first)
            .onFocusChanged { fieldFocused = it.isFocused; if (it.isFocused) keyboard?.show() }
            .onPreviewKeyEvent { e -> if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) { runCatching { next.requestFocus() }; true } else false },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(shape).background(AuraColors.Surface).border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) AuraColors.Focus else AuraColors.Border, shape).padding(horizontal = 20.dp, vertical = 14.dp)) {
                if (field.text.isEmpty()) Text(sheet.hint, style = AuraTypography.Body, color = AuraColors.TextMuted, maxLines = 1)
                inner()
            }
        },
    )
    Text("Press OK on the field to open the keyboard.", style = AuraTypography.Label, color = AuraColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Continue", style = AuraTypography.Button, color = if (ready) AuraColors.OnPrimaryAction else AuraColors.TextMuted, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).focusRequester(next).auraFocusable(shape) { if (ready) { keyboard?.hide(); sheet.onSubmit(value) } }.clip(shape).background(if (ready) AuraColors.PrimaryAction else AuraColors.Surface).padding(vertical = 12.dp))
        Text("Cancel", style = AuraTypography.Button, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).auraFocusable(shape, onClick = onClose).clip(shape).background(AuraColors.Surface).padding(vertical = 12.dp))
    }
}

@Composable
private fun CodePad(sheet: Sheet.CodeEntry, first: FocusRequester, onClose: () -> Unit) {
    var field by remember(sheet) { mutableStateOf(TextFieldValue("")) }
    val code = CodeInput.normalize(field.text, sheet.length)
    val problem = CodeInput.problem(field.text, sheet.length)
    val complete = problem == null && CodeInput.isComplete(code, sheet.length)
    val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val save = remember(sheet) { FocusRequester() }
    var fieldFocused by remember(sheet) { mutableStateOf(false) }
    var useRemotePad by remember(sheet) { mutableStateOf(false) }
    val remoteFirst = remember(sheet) { FocusRequester() }
    LaunchedEffect(useRemotePad) {
        if (useRemotePad) { keyboard?.hide(); withFrameNanos { }; runCatching { remoteFirst.requestFocus() } }
    }
    LaunchedEffect(sheet, windowFocused) {
        if (windowFocused && !useRemotePad) { withFrameNanos { }; runCatching { first.requestFocus() }; delay(120); keyboard?.show() }
    }
    Text(sheet.title, style = AuraTypography.SectionTitle)
    BasicTextField(
        value = field,
        onValueChange = { v -> if (!useRemotePad && v.text.length <= CodeInput.MAX_RAW) field = v },
        readOnly = useRemotePad,
        singleLine = true,
        textStyle = AuraTypography.SectionTitle.copy(letterSpacing = 4.sp),
        cursorBrush = SolidColor(AuraColors.Focus),
        visualTransformation = UpperCaseDisplay,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); runCatching { save.requestFocus() } }),
        modifier = Modifier.fillMaxWidth().focusRequester(first)
            .onFocusChanged {
                fieldFocused = it.isFocused
                if (it.isFocused && !useRemotePad) { keyboard?.show() }
            }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) {
                    runCatching { save.requestFocus() }
                    true
                } else false
            },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(shape).background(AuraColors.Surface).border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) AuraColors.Focus else AuraColors.Border, shape).padding(horizontal = 20.dp, vertical = 14.dp)) {
                if (field.text.isEmpty()) Text("AB12CD34", style = AuraTypography.SectionTitle.copy(letterSpacing = 4.sp), color = AuraColors.TextMuted)
                inner()
            }
        },
    )
    Text(problem ?: CodeInput.message(code, false, sheet.length), style = AuraTypography.Label, color = if (problem != null) AuraColors.AlertRed else AuraColors.TextSecondary)
    Text(if (useRemotePad) "Use the remote to choose letters and digits." else "Press OK on the code field to open the keyboard.", style = AuraTypography.Label, color = AuraColors.TextMuted)
    Text(if (useRemotePad) "Use TV keyboard instead" else "Use remote keypad (A–Z / 0–9)",
        style = AuraTypography.Button, color = AuraColors.TextPrimary,
        modifier = Modifier.fillMaxWidth().auraFocusable(shape, scale = 1f, elevation = 0.dp) {
            useRemotePad = !useRemotePad
            if (useRemotePad) keyboard?.hide() else runCatching { first.requestFocus() }
        }.clip(shape).background(AuraColors.SurfaceElevated).padding(horizontal = 20.dp, vertical = 12.dp))
    if (useRemotePad) {
        val keys = CodeInput.REMOTE_KEYS.map { it.toString() }
        // Six columns, six rows; every character is a real DPAD focus target and the keypad does not need an IME.
        keys.chunked(6).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEachIndexed { columnIndex, character ->
                    Text(character, style = AuraTypography.Button, textAlign = TextAlign.Center,
                        color = AuraColors.TextPrimary,
                        modifier = Modifier.then(if (rowIndex == 0 && columnIndex == 0) Modifier.focusRequester(remoteFirst) else Modifier)
                            .weight(1f).auraFocusable(shape, scale = 1f, elevation = 0.dp) {
                                if (code.length < sheet.length) field = TextFieldValue(code + character)
                            }.clip(shape).background(AuraColors.Surface).padding(vertical = 9.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backspace", style = AuraTypography.Button, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).auraFocusable(shape, scale = 1f, elevation = 0.dp) {
                    field = TextFieldValue(code.dropLast(1))
                }.clip(shape).background(AuraColors.Surface).padding(vertical = 10.dp))
            Text("Clear", style = AuraTypography.Button, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).auraFocusable(shape, scale = 1f, elevation = 0.dp) {
                    field = TextFieldValue("")
                }.clip(shape).background(AuraColors.Surface).padding(vertical = 10.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Save", style = AuraTypography.Button, color = if (complete) AuraColors.OnPrimaryAction else AuraColors.TextMuted, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).focusRequester(save).auraFocusable(shape) { if (complete) { keyboard?.hide(); sheet.onSubmit(code) } }.clip(shape).background(if (complete) AuraColors.PrimaryAction else AuraColors.Surface).padding(vertical = 12.dp))
        Text("Cancel", style = AuraTypography.Button, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).auraFocusable(shape, onClick = onClose).clip(shape).background(AuraColors.Surface).padding(vertical = 12.dp))
    }
}

@Composable
private fun Modifier.androidx_vscroll(): Modifier = this.verticalScroll(rememberScrollState())
