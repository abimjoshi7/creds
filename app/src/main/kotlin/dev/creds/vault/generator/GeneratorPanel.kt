package dev.creds.vault.generator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.creds.vault.core.domain.generator.Capitalization
import dev.creds.vault.core.domain.generator.CharacterClass
import dev.creds.vault.core.domain.generator.GeneratorMode
import dev.creds.vault.core.domain.generator.PassphraseOptions
import dev.creds.vault.core.domain.generator.PinOptions
import dev.creds.vault.core.domain.generator.RandomOptions
import dev.creds.vault.core.ui.theme.CredsRadiusMedium
import dev.creds.vault.core.ui.theme.SecretTextStyle
import dev.creds.vault.ui.StrengthMeter
import kotlin.math.roundToInt

object GeneratorTags {
    const val VALUE = "generator:value"
    const val REGENERATE = "generator:regenerate"
    const val COPY = "generator:copy"
    const val ENTROPY = "generator:entropy"
    const val LENGTH = "generator:length"
    const val AMBIGUOUS = "generator:ambiguous"
    const val REQUIRE_EACH = "generator:require-each"
    const val DIGIT = "generator:digit"
    const val USE = "generator:use"
    const val CLEAR_HISTORY = "generator:history:clear"
    fun mode(mode: GeneratorMode) = "generator:mode:${mode.name}"
    fun characterClass(cls: CharacterClass) = "generator:class:${cls.name}"
    fun separator(label: String) = "generator:separator:$label"
    fun capitalization(value: Capitalization) = "generator:caps:${value.name}"
    fun historyValue(id: Long) = "generator:history:$id"
    fun historyReveal(id: Long) = "generator:history:$id:reveal"
    fun historyCopy(id: Long) = "generator:history:$id:copy"
    fun historyDelete(id: Long) = "generator:history:$id:delete"
}

/** Everything the generator panel and screen can ask for; defaults are no-ops for tests. */
data class GeneratorActions(
    val onRegenerate: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onMode: (GeneratorMode) -> Unit = {},
    val onLength: (Int) -> Unit = {},
    val onToggleClass: (CharacterClass) -> Unit = {},
    val onExcludeAmbiguous: (Boolean) -> Unit = {},
    val onRequireEach: (Boolean) -> Unit = {},
    val onWords: (Int) -> Unit = {},
    val onSeparator: (String) -> Unit = {},
    val onCapitalization: (Capitalization) -> Unit = {},
    val onIncludeDigit: (Boolean) -> Unit = {},
    val onPinLength: (Int) -> Unit = {},
)

/** Separators offered for passphrases, with how each is named on screen. */
internal val PassphraseSeparators: List<Pair<String, String>> = listOf(
    "-" to "Hyphen",
    " " to "Space",
    "." to "Period",
    "_" to "Underscore",
    "" to "None",
)

/**
 * The value, its strength, and the options for the current mode.
 *
 * The value is shown in full. Seeing it is the point of a generator, and `FLAG_SECURE`
 * still keeps it out of screenshots and recordings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorPanel(
    state: GeneratorUiState,
    actions: GeneratorActions,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            GeneratorMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.mode == mode,
                    onClick = { actions.onMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, GeneratorMode.entries.size),
                    modifier = Modifier.testTag(GeneratorTags.mode(mode)),
                ) {
                    Text(mode.label)
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(CredsRadiusMedium),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            ) {
                Text(
                    state.generated?.value.orEmpty(),
                    style = SecretTextStyle,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .padding(vertical = 12.dp)
                        .testTag(GeneratorTags.VALUE),
                )
                IconButton(onClick = actions.onRegenerate, modifier = Modifier.testTag(GeneratorTags.REGENERATE)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Generate another")
                }
                IconButton(
                    onClick = actions.onCopy,
                    enabled = state.generated != null,
                    modifier = Modifier.testTag(GeneratorTags.COPY),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                }
            }
        }

        state.generated?.let { generated ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${generated.entropyBits.roundToInt()} bits of entropy",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag(GeneratorTags.ENTROPY),
                )
                state.strength?.let { strength ->
                    StrengthMeter(
                        band = strength.band,
                        crackTime = strength.crackTimeDisplay,
                        warning = strength.warning,
                        suggestions = emptyList(),
                    )
                }
            }
        }

        when (settings.mode) {
            GeneratorMode.RANDOM -> RandomOptionsSection(settings.random, actions)
            GeneratorMode.PASSPHRASE -> PassphraseOptionsSection(settings.passphrase, actions)
            GeneratorMode.PIN -> LabelledSlider(
                label = "Digits",
                value = settings.pin.effectiveLength,
                range = PinOptions.MIN_LENGTH..PinOptions.MAX_LENGTH,
                onChange = actions.onPinLength,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RandomOptionsSection(options: RandomOptions, actions: GeneratorActions) {
    val enabled = options.effectiveClasses
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelledSlider(
            label = "Length",
            value = options.effectiveLength,
            range = RandomOptions.MIN_LENGTH..RandomOptions.MAX_LENGTH,
            onChange = actions.onLength,
            modifier = Modifier.testTag(GeneratorTags.LENGTH),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CharacterClass.entries.forEach { cls ->
                val on = cls in enabled
                FilterChip(
                    selected = on,
                    // The last class on cannot be switched off; say so by disabling it.
                    enabled = !(on && enabled.size == 1),
                    onClick = { actions.onToggleClass(cls) },
                    label = { Text(cls.label) },
                    modifier = Modifier.testTag(GeneratorTags.characterClass(cls)),
                )
            }
        }
        SwitchRow(
            label = "Avoid look-alikes (l I 1 O 0)",
            checked = options.excludeAmbiguous,
            onChange = actions.onExcludeAmbiguous,
            tag = GeneratorTags.AMBIGUOUS,
        )
        if (enabled.size > 1) {
            SwitchRow(
                label = "At least one of each",
                checked = options.requireEachClass,
                onChange = actions.onRequireEach,
                tag = GeneratorTags.REQUIRE_EACH,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseOptionsSection(options: PassphraseOptions, actions: GeneratorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelledSlider(
            label = "Words",
            value = options.effectiveWords,
            range = PassphraseOptions.MIN_WORDS..PassphraseOptions.MAX_WORDS,
            onChange = actions.onWords,
        )
        Text("Separator", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PassphraseSeparators.forEach { (separator, label) ->
                FilterChip(
                    selected = options.separator == separator,
                    onClick = { actions.onSeparator(separator) },
                    label = { Text(label) },
                    modifier = Modifier.testTag(GeneratorTags.separator(label)),
                )
            }
        }
        Text("Capitals", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Capitalization.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = options.capitalization == value,
                    onClick = { actions.onCapitalization(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, Capitalization.entries.size),
                    modifier = Modifier.testTag(GeneratorTags.capitalization(value)),
                ) {
                    Text(value.label)
                }
            }
        }
        SwitchRow(
            label = "Add a digit",
            checked = options.includeDigit,
            onChange = actions.onIncludeDigit,
            tag = GeneratorTags.DIGIT,
        )
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("$value", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp)
            .testTag(tag),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val GeneratorMode.label: String
    get() = when (this) {
        GeneratorMode.RANDOM -> "Password"
        GeneratorMode.PASSPHRASE -> "Passphrase"
        GeneratorMode.PIN -> "PIN"
    }

private val CharacterClass.label: String
    get() = when (this) {
        CharacterClass.UPPERCASE -> "A–Z"
        CharacterClass.LOWERCASE -> "a–z"
        CharacterClass.DIGITS -> "0–9"
        CharacterClass.SYMBOLS -> "!@#"
    }

private val Capitalization.label: String
    get() = when (this) {
        Capitalization.LOWER -> "lower"
        Capitalization.TITLE -> "Title"
        Capitalization.UPPER -> "UPPER"
    }
