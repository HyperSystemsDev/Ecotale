# Ecotale Placeholders

Expansion identifier: `ecotale`

**Syntax:**
- PlaceholderAPI (HelpChat): `%ecotale_<placeholder>%`
- WiFlowPlaceholderAPI: `{ecotale_<placeholder>}`

---

## 1. Balance (5)

| # | Placeholder | Example Output | Description |
|---|-------------|----------------|-------------|
| 1 | `balance` | `1500.5` | Raw balance (no formatting) |
| 2 | `balance_formatted` | `$1,500.50` | Full formatted balance with currency symbol |
| 3 | `balance_short` | `$1.5K` | Compact format (K/M/B) |
| 4 | `balance_commas` | `1,500` | Comma-separated, no decimals, no symbol |
| 5 | `balance_<n>dp` | `1500.500` (3dp) | Custom decimal places (1-10). E.g. `balance_2dp`, `balance_5dp` |

## 2. Activity (4)

| # | Placeholder | Example Output | Description |
|---|-------------|----------------|-------------|
| 6 | `profit` | `+$13,000.00` | Lifetime net profit (totalEarned - totalSpent) |
| 7 | `profit_ratio` | `2.08x` | Earning efficiency (totalEarned / totalSpent). `INF` if never spent |
| 8 | `session_change` | `+$350.00` | Balance change since login. `N/A` if no session data |
| 9 | `last_activity` | `3m ago` | Time since last transaction (s/m/h/d/mo). `Never` if no transactions |

## 3. Rank (3)

| # | Placeholder | Example Output | Description |
|---|-------------|----------------|-------------|
| 10 | `rank` | `#7` | Player's wealth rank among all accounts |
| 11 | `rank_suffix` | `7th` | Rank with English ordinal suffix (1st, 2nd, 3rd, 4th...) |
| 12 | `rank_percentile` | `Top 5%` | Wealth percentile bracket (Top 1%, 5%, 10%, 25%, 50%) |

## 4. Competitive (3)

| # | Placeholder | Example Output | Description |
|---|-------------|----------------|-------------|
| 13 | `gap_to_first` | `$48,500.00` | How much you need to reach #1. `$0.00` if you are #1 |
| 14 | `gap_to_next` | `$2,300.00` | How much you need to pass the player above you. `You're #1!` if first |
| 15 | `ahead_of` | `342 players` | Number of players with less wealth than you |

## 5. Server Economy (4)

| # | Placeholder | Example Output | Description |
|---|-------------|----------------|-------------|
| 16 | `server_total` | `$1.2M` | Total money in circulation (all players combined) |
| 17 | `server_average` | `$3,654.97` | Average balance per player |
| 18 | `server_median` | `$1,200.00` | Median balance (true middle value, not average) |
| 19 | `server_players` | `342` | Total number of player accounts |

## 6. Session Trend (4)

| # | Placeholder | Example Output | Description |
|---|-------------|----------------|-------------|
| 20 | `trend_session` | `+$350.00` | Formatted balance change since login |
| 21 | `trend_session_percent` | `+12.5%` | Percentage change since login |
| 22 | `trend_session_arrow` | `UP` | Direction indicator: `UP`, `DOWN`, or `--` |
| 23 | `trend_session_label` | `UP +$350.00` | Combined arrow + amount label |

## 7. Config (2)

| # | Placeholder | Example Output | Description |
|---|-------------|----------------|-------------|
| 24 | `currency_symbol` | `$` | Configured currency symbol |
| 25 | `currency_name` | `Bank` | Configured currency display name |

## 8. Dynamic Leaderboard (variable)

| Placeholder | Example Output | Description |
|-------------|----------------|-------------|
| `top_name_<n>` | `Notch` | Name of the player at rank N (1-100) |
| `top_balance_<n>` | `$50,000.00` | Formatted balance of the player at rank N (1-100) |

Examples: `top_name_1` (richest player), `top_name_10`, `top_balance_1`, `top_balance_5`

---

## Quick Test List (copy-paste)

### PlaceholderAPI (HelpChat) format:
```
%ecotale_balance%
%ecotale_balance_formatted%
%ecotale_balance_short%
%ecotale_balance_commas%
%ecotale_balance_2dp%
%ecotale_profit%
%ecotale_profit_ratio%
%ecotale_session_change%
%ecotale_last_activity%
%ecotale_rank%
%ecotale_rank_suffix%
%ecotale_rank_percentile%
%ecotale_gap_to_first%
%ecotale_gap_to_next%
%ecotale_ahead_of%
%ecotale_server_total%
%ecotale_server_average%
%ecotale_server_median%
%ecotale_server_players%
%ecotale_trend_session%
%ecotale_trend_session_percent%
%ecotale_trend_session_arrow%
%ecotale_trend_session_label%
%ecotale_currency_symbol%
%ecotale_currency_name%
%ecotale_top_name_1%
%ecotale_top_name_2%
%ecotale_top_name_3%
%ecotale_top_balance_1%
%ecotale_top_balance_2%
%ecotale_top_balance_3%
```

### WiFlowPlaceholderAPI format:
```
{ecotale_balance}
{ecotale_balance_formatted}
{ecotale_balance_short}
{ecotale_balance_commas}
{ecotale_balance_2dp}
{ecotale_profit}
{ecotale_profit_ratio}
{ecotale_session_change}
{ecotale_last_activity}
{ecotale_rank}
{ecotale_rank_suffix}
{ecotale_rank_percentile}
{ecotale_gap_to_first}
{ecotale_gap_to_next}
{ecotale_ahead_of}
{ecotale_server_total}
{ecotale_server_average}
{ecotale_server_median}
{ecotale_server_players}
{ecotale_trend_session}
{ecotale_trend_session_percent}
{ecotale_trend_session_arrow}
{ecotale_trend_session_label}
{ecotale_currency_symbol}
{ecotale_currency_name}
{ecotale_top_name_1}
{ecotale_top_name_2}
{ecotale_top_name_3}
{ecotale_top_balance_1}
{ecotale_top_balance_2}
{ecotale_top_balance_3}
```

---


