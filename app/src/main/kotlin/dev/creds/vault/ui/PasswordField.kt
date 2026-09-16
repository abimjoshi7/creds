package dev.creds.vault.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.creds.vault.core.ui.theme.SecretTextStyle

/**
 * A master-password field with a reveal toggle.
 *
 * One component rather than a copy per screen, because the security-relevant details are
 * easy to get subtly wrong in one place and not another: obscured by default, and the
 * reveal state deliberately reset rather than remembered.
 *
 * [remember] rather than `rememberSaveable` is the important choice. A saveable flag
 * would survive a rotation or process death and bring the field back *already revealed*,
 * possibly in front of somebody else. Resetting to obscured costs the user one tap and
 * removes that whole class of surprise.
 *
 * When revealed the value is drawn in [SecretTextStyle] — monospace with wide letter
 * spacing — because the only reason to reveal a password is to read it character by
 * character, and that is exactly when `l` versus `1` and `O` versus `0` matter.
 *
 * `FLAG_SECURE` still applies while revealed, so the plaintext cannot be screenshotted
 * or screen-recorded; it is visible to whoever is looking at the screen, which is the
 * point.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fieldTag: String,
    revealTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
) {
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        shape = MaterialTheme.shapes.medium,
        textStyle = if (revealed) SecretTextStyle else LocalTextStyle.current,
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        trailingIcon = {
            IconButton(
                onClick = { revealed = !revealed },
                enabled = enabled,
                modifier = Modifier.testTag(revealTag),
            ) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    // Describes the action, not the state: a screen reader user needs to
                    // know what the button will do, not what it already did.
                    contentDescription = if (revealed) "Hide password" else "Show password",
                )
            }
        },
        modifier = modifier.testTag(fieldTag),
    )
}
