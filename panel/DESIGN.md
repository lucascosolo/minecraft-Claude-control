# Local operator console

This is a basic operating surface for a desktop Minecraft server. The user pinned familiar start/stop controls and console input/output, so the interface uses conventional controls rather than a visual concept tournament.

The page uses a dark slate background, a darker console, pale text and muted green primary buttons. System UI type serves labels; monospaced type serves console output and commands. The main composition is a single console with start/stop controls above it and command entry below it. Server state sits above the console and join instructions follow it.

Controls have keyboard focus states, accessible labels, disabled/loading states and useful errors. Console content is rendered with textContent. At narrow widths the header and command row stack, and the console remains scrollable.

One desktop and phone screenshot pass confirmed readable controls, no horizontal overflow, and a usable console composition. Backend security and console behavior are verified separately by tests. Actual Docker availability was not established; screenshots show the loading/connecting state, not a running Minecraft server. No invented telemetry is displayed.
