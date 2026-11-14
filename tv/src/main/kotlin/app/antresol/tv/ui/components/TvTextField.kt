package app.antresol.tv.ui.components

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

/**
 * Whether the on-screen keyboard is up, so a screen can move its [TvTextField] above it.
 *
 * The window's own insets say so on most TVs. They are read from the root (polled while the screen
 * shows): Compose's `WindowInsets.isImeVisible` needs an edge-to-edge window to get insets at all,
 * and without them it reports a keyboard that is not there. Some TV keyboards (floating panels)
 * report no IME at all: until the insets have once shown a keyboard, the field's own
 * `keyboard.show()` counts, and it stops counting when the field submits, loses focus or gets a
 * D-pad key (a keyboard that is up takes the D-pad itself, so a key reaching the field means it is
 * gone).
 */
@Composable
fun rememberTvKeyboardOpen(): Boolean {
    val view = LocalView.current
    var insetsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(view) {
        while (true) {
            val visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (visible) TvKeyboard.insetsSeen = true
            insetsVisible = visible
            delay(KEYBOARD_POLL_MS)
        }
    }
    return insetsVisible || (TvKeyboard.requested && !TvKeyboard.insetsSeen)
}

private const val KEYBOARD_POLL_MS = 150L

/** The fallback of [rememberTvKeyboardOpen]: one window, so one keyboard. */
internal object TvKeyboard {
    var requested by mutableStateOf(false)
    var insetsSeen by mutableStateOf(false)
}

/**
 * A single-line text field for the remote: it takes D-pad focus like any control (white outline),
 * OK opens the on-screen keyboard, and the keyboard's action key calls [onSubmit].
 *
 * Addresses and passwords get no word suggestions ([suggestions]): autocorrect off is not enough
 * for Gboard, it needs `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, which Compose never sets.
 * A screen with the field low on the page moves it up while [rememberTvKeyboardOpen].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    leadingIcon: ImageVector? = null,
    suggestions: Boolean = !password && keyboardType == KeyboardType.Text,
) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            nextHandler.startInputMethod(if (suggestions) request else NoSuggestions(request))
        },
    ) {
        Field(value, onValueChange, placeholder, onSubmit, modifier, password, keyboardType, imeAction, leadingIcon)
    }
}

/** Adds `TYPE_TEXT_FLAG_NO_SUGGESTIONS` to what Compose asks of the keyboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class NoSuggestions(private val request: PlatformTextInputMethodRequest) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = request.createInputConnection(outAttributes)
        if (outAttributes.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        return connection
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    modifier: Modifier,
    password: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    leadingIcon: ImageVector?,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Antresol.typography.bodyEmphasis.copy(color = AntresolColors.Label),
        cursorBrush = SolidColor(AntresolColors.Accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onAny = {
            keyboard?.hide()
            TvKeyboard.requested = false
            onSubmit()
        }),
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) TvKeyboard.requested = false
            }
            .onPreviewKeyEvent { event ->
                if (event.key in DpadMoves && event.type == KeyEventType.KeyDown) TvKeyboard.requested = false
                when (event.key) {
                    // The remote's OK key opens the keyboard instead of doing nothing.
                    Key.DirectionCenter -> {
                        if (event.type == KeyEventType.KeyUp) {
                            keyboard?.show()
                            TvKeyboard.requested = true
                        }
                        event.type == KeyEventType.KeyUp
                    }
                    // A single-line field would eat Up/Down to move its cursor; leave the field instead.
                    Key.DirectionDown, Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown) {
                            focusManager.moveFocus(if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                        }
                        true
                    }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(if (focused) AntresolColors.FillPrimary else AntresolColors.FillTertiary, AntresolShapes.control)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) AntresolColors.Label else AntresolColors.Hairline,
                        shape = AntresolShapes.control,
                    )
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = AntresolColors.LabelSecondary, modifier = Modifier.size(20.dp))
                    }
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = Antresol.typography.body, color = AntresolColors.LabelTertiary, maxLines = 1)
                        }
                        inner()
                    }
                }
            }
        },
    )
}

private val DpadMoves = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * The page of a screen whose [TvTextField] sits in the middle (server address, profile password):
 * centred at rest, and while [keyboardOpen] it slides to the top of the screen so the keyboard
 * (a panel over the middle and bottom of a TV screen, Gboard for TV floats there) never covers the
 * field. Put what may go while typing (logo, avatar, subtitle) in [WhileNotTyping].
 */
@Composable
fun KeyboardAwarePage(
    keyboardOpen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val bias by animateFloatAsState(
        targetValue = if (keyboardOpen) -1f else 0f,
        animationSpec = tween(AntresolMotion.MEDIUM_MS, easing = AntresolMotion.easeOut),
        label = "keyboard-page",
    )
    Box(
        modifier = modifier.padding(top = KeyboardTopGap, bottom = KeyboardTopGap),
        contentAlignment = BiasAlignment(0f, bias),
        content = content,
    )
}

/** Logo, avatar or subtitle: folds away while the keyboard is open (see [KeyboardAwarePage]). */
@Composable
fun ColumnScope.WhileNotTyping(keyboardOpen: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = !keyboardOpen,
        enter = fadeIn(tween(AntresolMotion.MEDIUM_MS)) + expandVertically(tween(AntresolMotion.MEDIUM_MS, easing = AntresolMotion.easeOut)),
        exit = fadeOut(tween(AntresolMotion.FAST_MS)) + shrinkVertically(tween(AntresolMotion.MEDIUM_MS, easing = AntresolMotion.easeOut)),
    ) { content() }
}

/** The gap above a field that rode up over the keyboard: the TV's overscan-safe top margin. */
private val KeyboardTopGap = 40.dp
