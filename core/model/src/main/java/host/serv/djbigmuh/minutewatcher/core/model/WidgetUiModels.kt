package host.serv.djbigmuh.minutewatcher.core.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.util.Locale

/**
 * Widget state for rendering.
 * Contains all data necessary to render the widget.
 *
 * @property layoutMode Widget layout mode.
 * @property simArrangement SIM arrangement.
 * @property backgroundColor Background color.
 * @property transparency Background transparency.
 * @property simUiStates List of SIM card states.
 * @property isHorizontal Horizontal arrangement.
 * @property showSeparator Show separator.
 * @property useSystemColors Use system colors.
 * @property settingsLoadFailed Settings load error flag.
 * @property refreshFailed Data refresh error flag.
 * @property isSnapshotEmpty Empty snapshot flag.
 */
@Immutable
data class WidgetUiState(
    val layoutMode: Int,
    val simArrangement: Int,
    val backgroundColor: Int,
    val transparency: Int,
    val simUiStates: List<SimUiState>,
    val isHorizontal: Boolean = true,
    val showSeparator: Boolean = false,
    val useSystemColors: Boolean = false,
    val settingsLoadFailed: Boolean = false,
    val simListLoadFailed: Boolean = false,
    val refreshFailed: Boolean = false,
    val isSnapshotEmpty: Boolean = false,
    val strings: WidgetStrings =
        WidgetStrings(
            usedText = "",
            minutesAbbr = "",
            limitText = "",
            gbAbbr = "",
        ),
)

/**
 * Widget strings.
 * Created via WidgetStringsFactory.create(context).
 * Recreated on configuration change (locale).
 *
 * @property usedText "Used" text.
 * @property minutesAbbr Minutes abbreviation (e.g., "min.").
 * @property limitText "Limit" text.
 * @property gbAbbr Gigabytes abbreviation ("GB").
 * @property refreshErrorText "Refresh error" text.
 * @property settingsLoadErrorText "Settings load error" text.
 * @property noDataText "No data" text.
 * @property noSimFoundText "No SIM found" text.
 * @property staleDataWarningText "Stale data warning" text.
 * @property simStatusSummaryFormat Format string for SIM status accessibility summary.
 * @property valuePlaceholder Placeholder string for absent values ("—").
 * @property labelValueFormat Format string for label-value colon pairs ("%1$s: %2$s").
 * @property limitSuffixFormat Format string for limit suffix ("/%1$s").
 * @property locale Effective locale for formatting.
 */
@Immutable
data class WidgetStrings(
    val usedText: String,
    val minutesAbbr: String,
    val limitText: String,
    val gbAbbr: String,
    val refreshErrorText: String = "",
    val settingsLoadErrorText: String = "",
    val noDataText: String = "",
    val noSimFoundText: String = "",
    val staleDataWarningText: String = "",
    val simStatusSummaryFormat: String = "",
    val valuePlaceholder: String = "—",
    val labelValueFormat: String = "%1\$s: %2\$s",
    val limitSuffixFormat: String = "/%1\$s",
    val locale: Locale = Locale.US,
)

/**
 * State of a single SIM card for rendering in the widget.
 *
 * @property slot SIM slot index.
 * @property operatorName Operator name.
 * @property operatorIconRes Operator icon resource.
 * @property showSim Show SIM card.
 * @property showIcon Show icon.
 * @property showName Show name.
 * @property showMinutes Show minutes.
 * @property minutesText Minutes text.
 * @property minutesColor Minutes color.
 * @property showMinutesLimit Show minutes limit.
 * @property minutesLimitText Minutes limit text.
 * @property showTraffic Show traffic.
 * @property trafficText Traffic text.
 * @property trafficColor Traffic color.
 * @property showTrafficLimit Show traffic limit.
 * @property trafficLimitText Traffic limit text.
 * @property fontMultiplier Font size multiplier.
 * @property textColor Text color.
 * @property secondaryTextColor Secondary text color.
 * @property minutesUsedFullText Full text of used minutes.
 * @property minutesLimitFullText Full text of minutes limit.
 * @property trafficUsedFullText Full text of used traffic.
 * @property trafficLimitFullText Full text of traffic limit.
 * @property layoutSpec Layout specification for SimLayoutRender.
 */
@Immutable
data class SimUiState(
    val slot: Int,
    val operatorName: String,
    val operatorIconRes: Int,
    val showSim: Boolean,
    val showIcon: Boolean,
    val showName: Boolean,
    val showMinutes: Boolean,
    val minutesText: String,
    val minutesSuffix: String = "",
    val minutesColor: Int,
    val showMinutesLimit: Boolean,
    val minutesLimitText: String,
    val showTraffic: Boolean,
    val trafficText: String,
    val trafficSuffix: String = "",
    val trafficColor: Int,
    val showTrafficLimit: Boolean,
    val trafficLimitText: String,
    val fontMultiplier: Float,
    val textColor: Int,
    val secondaryTextColor: Int,
    // Detailed fields for SIX_LINES mode
    val minutesUsedFullText: String = "",
    val minutesLimitFullText: String = "",
    val trafficUsedFullText: String = "",
    val trafficLimitFullText: String = "",
    val layoutSpec: SimLayoutSpec = SimLayoutSpec(emptyList()),
)

/**
 * Layout specification for SimLayoutRender.
 * Contains a list of rows, each containing components.
 *
 * @property rows List of layout rows.
 */
@Immutable
data class SimLayoutSpec(
    val rows: List<SimLayoutRow>,
)

/**
 * Layout row for SimLayoutRender.
 * Contains a list of components.
 *
 * @property components List of row components.
 */
@Immutable
data class SimLayoutRow(
    val components: List<SimLayoutComponent>,
)

/**
 * Layout component for SimLayoutRender.
 * Sealed class ensures exhaustive processing of all variants.
 *
 * Variants:
 * - [Icon] — icon (iconRes, sizeDp).
 * - [Text] — text (text, color, fontSizeSp, isBold).
 * - [Spacer] — spacer (widthDp, heightDp).
 */
@Stable
sealed interface SimLayoutComponent {
    @Immutable
    data class Icon(
        val iconRes: Int,
        val sizeDp: Int,
    ) : SimLayoutComponent

    @Immutable
    data class Text(
        val text: String,
        val color: Int,
        val fontSizeSp: Float,
        val isBold: Boolean = false,
    ) : SimLayoutComponent

    @Immutable
    data class Spacer(
        val widthDp: Int = 0,
        val heightDp: Int = 0,
    ) : SimLayoutComponent
}
