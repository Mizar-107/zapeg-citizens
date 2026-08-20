# Trusted workflow: building

Use this workflow before designing or constructing a non-trivial structure.

1. Resolve the site from trusted job context: dimension, anchor, allowed region, ground level and
   protected cells. Inspect the terrain. If the player's words are deictic ("here", "this plot"),
   require a recorded position/look target/selection rather than guessing.
2. Plan before mutation. Record footprint, storeys, palette, rooms, entrances and roof, then derive a
   deterministic cell plan and material bill. Large surfaces should mix a main material with roughly
   10-20% compatible texture variation.
3. Use exactly one floor slab per storey. Put walls above the floor, cut two-block-tall door openings
   after walls, and keep an unobstructed route from outside through the lower door cell. Use `walls`
   for perimeter rings instead of stacking a hollow box on a foundation.
4. Build big to small in ordered `build.ops`: foundation, walls/frame, roof, air openings, doors and
   windows, stairs, interior fittings, lighting, then exterior details. Later ops overwrite earlier
   cells. Prefer slab roofs with an overhang and contrasting ridge/eave bands; a flat lid rarely reads
   as a finished villa.
5. In creative mode `build` is fast and material-free. In survival, freeform `build` preflights the
   entire call against the citizen's inventory and places nothing if any material is short. For a
   structure larger than one inventory, use a persisted/resumable plan or blueprint supply stages;
   do not begin a shell that cannot be finished.
6. Never include water or lava in `build` ops. Prepare a basin and handle liquids separately. Keep
   every requested cell inside the authorized region.
7. Verify after construction: planned block states match, exterior doors and stairs are passable,
   rooms are reachable and furnished, the roof is closed, unsupported detail items did not drop, and
   interior floors are sufficiently lit. Patch only observed discrepancies. Survey, build, and verify
   are one job; do not stop after the first `build` call if the original instruction is unfinished.

There is no `place_block` tool. Place an exact block with `build` and a one-cell `set` op.
