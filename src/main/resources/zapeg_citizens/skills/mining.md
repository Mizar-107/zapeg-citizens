# Trusted workflow: mining

Use this workflow for gathering ores, stone, logs, or other mineable blocks.

1. Call `get_self_status`. Confirm dimension, health, food, and free inventory slots.
   Logs, wood, leaves, dirt, sand, gravel, and similar vanilla hand-breakable blocks do **not**
   require a tool: punching with empty hands is valid and expected. Call `mine` immediately.
   An axe, shovel, or shears is optional speed only. Equip one if it is already in inventory;
   never pause the job to ask the owner for it. A pickaxe **is** required for stone and ore;
   only then is a missing tool a real blocker.
2. Translate the resource into every relevant exact block id. For diamonds this normally means both
   `minecraft:diamond_ore` and `minecraft:deepslate_diamond_ore`. The `mine.count` value is NEW items
   to gain above the starting inventory, not blocks to break; Fortune can make the result exceed it.
3. Prefer one `mine` call for a bounded count. It finds indexed matches, paths through terrain, digs
   and collects its own target drops. Do not send a separate `goto` to ore coordinates first and do
   not launch another body action while mining is active.
4. Numen 0.1.1 does not prospect indefinitely when it knows of no matching block. A no-target result
   means the currently loaded/searchable area was exhausted, not that the dimension contains none.
   A durable gathering job should move through explicit bounded search sectors, checkpoint, rescan,
   and stop at its configured search budget.
5. On wrong-tool, full-inventory, timeout or restart, observe inventory and nearby target blocks again
   before retrying. Count actual item delta so already-carried resources are never reported as new.
6. If delivery, crafting, or return was requested in the original instruction, that is the same
   job. After `mine` succeeds, continue to the next remaining step without waiting for another
   player command. Open the exact destination container and use `transfer`, then verify its
   inventory delta and return to the recorded home/actor position.

There is no `break_block` tool. For one intentional exact break, move within reach and use
`interact_at` with button=`left` and hold_ticks=`-1`.
