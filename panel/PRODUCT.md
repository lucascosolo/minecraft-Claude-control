# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Implementation delegated by the user's instruction to complete the work: plain HTML, CSS and JavaScript with a local Python standard-library server. Docker runs Minecraft; the panel remains on the desktop host.

## Users

Lucas operates the desktop Minecraft server so he and his wife can play together on Java or Bedrock.

## Product Purpose

Start and stop the Minecraft container, send console commands, and watch its output in one basic browser panel.

## Operating Context

The server uses a fresh world with ClaudeControl as its focal point, Geyser cross-play and Floodgate authentication. API credentials are kept in the private Minecraft plugin config. The panel is local to the desktop and is not a public server-management endpoint.

## Capabilities and Constraints

Container start and stop, status, console output, command input and autoscroll. It manages only the named Minecraft container. There is no arbitrary host shell or filesystem browser. Docker socket access is required on the host, and is currently unavailable to this agent session. The startup script will perform the last host-only actions when the user runs it.

## Brand Commitments

Basic, familiar operator controls. Function and readability take priority over decoration.

## Product Principles

- Preserve server data when stopping or replacing runtime assets.
- Show actual state and actionable errors rather than invented telemetry.
- Keep the control panel local and credentials out of console display.
