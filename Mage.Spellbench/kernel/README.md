# Lockstep patches for Jack's kernel

The lockstep match seeds mtg-kernel with XMage's hidden zones, so both engines play the same game. Two local
patches, against the commits the `pauper-kernel` board pins:

- `mtg-kernel-92ec2393.patch` (jackmaiorino/mtg-kernel `92ec2393`): the bridge's `reset` takes optional
  `library_orders` (each seat's card names, top first, instead of a shuffle of the seed; the opening deal is
  unchanged), and a new `arrange_library` request puts a seat's library in a given order after XMage shuffled it.
  Both copies of the game (the session and the Archon's flat-v4 feature copy) take the same order.
- `spellbench-27c9009.patch` (jackmaiorino/spellbench `27c9009`): `kernel_engine_v2.py` passes them through as two
  private request types, `x_lockstep_reset` (a standard `reset` plus `library_orders`) and
  `x_lockstep_arrange_library`, mapping wire seats to the bridge's seats. Standard requests are unchanged.

```bash
git -C mtg-kernel apply ../Mage.Spellbench/kernel/mtg-kernel-92ec2393.patch
```

`check_seeded_reset.py` checks both against a built bridge.
