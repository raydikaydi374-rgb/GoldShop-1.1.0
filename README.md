# GoldShop 1.1.0

Paper 1.21.1 / Java 21 plugin source.

## Build
Run `gradle build` with Gradle installed. Output: `build/libs/GoldShop-1.1.0.jar`.

## Commands
- `/goldshop` open the shop
- `/goldshop help` then `/goldshop help ar` or `/goldshop help en`
- `/goldshop reload` reload configuration (admin)
- `/goldshop mission create <id>`
- `/goldshop mission delete <id>`
- `/goldshop mission toggle <id>`
- `/goldshop mission reward <id> <amount>`
- `/goldshop mission target <id> <amount>`
- `/gold` show balance
- `/gold give|take|set <player> <amount>`
- `/gold rename <currency name>`
- `/goldmissions` open missions

## Configuration
Edit `plugins/GoldShop/config.yml`:
- `currency.name`, menu title/size/filler, category slots/materials, products/prices/commands.
- `command-items`: clickable items that execute configured player or console commands. Use `{player}`.
- `missions.entries`: JOIN, KILL, PLAYTIME and VOTE mission types; target, reward, enabled, repeatable.
- `voting`: reward and cooldown.
Messages are in `messages.yml`. Player balances and mission progress are stored in `balances.yml` and `players.yml`.

## Integrations
- PlaceholderAPI (optional): `%goldshop_balance%`, `%goldshop_currency%`.
- NuVotifier (optional): listens for VotifierEvent when NuVotifier is installed. Vote reward is applied to an online player matching the vote username. Configure your voting sites and NuVotifier separately.

## Important testing notes
This source archive has not been compiled or tested on a live Paper server in this environment. Build it first and test on a staging server. Configure product delivery commands and external plugin commands to match your server. Some advanced GUI styling (HEX/gradient text, per-item custom model data/glow, category pagination, and offline vote crediting) is not implemented in this initial 1.1.0 source and should not be assumed available.
