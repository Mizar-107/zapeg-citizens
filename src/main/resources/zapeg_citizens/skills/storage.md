# Trusted workflow: storage

Use this workflow for sorting, consolidating, or moving items among containers.

1. Establish an explicit scope before touching storage. Prefer the actor's recorded selection or
   exact container coordinates. "These chests" is not enough without that context. Never expand the
   scope merely because another chest is nearby.
2. Inspect before moving. Record every source container and the exact item stacks it holds, including
   counts, damage and tags. Decide a deterministic category and destination for every stack. Preserve
   custom names, enchantments and contents; visually similar stacks are not necessarily identical.
3. Keep capacity in the plan. Reserve destination slots before removing anything. If capacity is
   short and the job permits new storage, choose an accessible empty cell, obtain a chest, place one
   single chest, verify it, then include it as a destination. Do not assume two directly-set chest
   blocks will form a valid double chest.
4. With Numen primitives, visit one exact container at a time: move beside it, right-click it with
   `interact_at`, call `inspect_gui`, perform all safe slot moves for that GUI in one `transfer` call,
   then `close_gui`. `transfer` cannot move directly from one closed container to another; use the
   citizen inventory as bounded transit space. If gathering, crafting, or another earlier step was
   part of the same original instruction, continue into this storage work without waiting for a new
   player command.
5. Checkpoint after each closed container. On retry or restart, inspect again and reconcile actual
   contents instead of replaying old slot indices. Slot numbers and stack sizes may have changed.
6. Completion requires conservation evidence: every original stack is present exactly once, all
   destinations satisfy the category policy, no cursor item or dropped item remains, and containers
   outside the scope are unchanged.

There is no `deposit_items` tool. Deposit by opening the intended container and using `transfer`.
