# NPC implementation references

The NPC feature was implemented in Regnum Economy with original Java code and five original skin textures. The native implementation adds no external NPC dependency.

[Easy NPC Core, Minecraft 1.21.1 / Fabric, version 7.12.1](https://github.com/MarkusBordihn/BOs-Easy-NPC/tree/1.21.1/core) by Markus Bordihn was consulted for its common entity registration, NPC components and saved data, dialogue and condition handling, configured trades, client renderer separation, and play networking. The [Config UI module](https://github.com/MarkusBordihn/BOs-Easy-NPC/tree/1.21.1/config-ui) illustrates the separate editor and its Core dependency. The [upstream 1.21.1 license](https://github.com/MarkusBordihn/BOs-Easy-NPC/blob/1.21.1/LICENSE.md) applies MIT terms to code, with a separate reserved license for artistic assets.

| Option | Assessment |
| --- | --- |
| Easy NPC dependency | Supplies mature entity variants and editing. Economy would still need a journal-backed settlement adapter and Nations authorization for every action. |
| Selective source adaptation | MIT-covered pieces could be adapted with notices. The specialized model, data and configuration interfaces would add maintenance as upstream and Regnum evolve. |
| Native implementation | Uses vanilla entity storage, Fabric play packets and Regnum's existing settlement and permissions. Regnum owns the screen, configuration and asset lifecycle. |

Easy NPC is a source reference only. Regnum ships its own implementation and artwork under this repository's existing license. The existing Fabric and Nations dependencies are used under their existing terms.
